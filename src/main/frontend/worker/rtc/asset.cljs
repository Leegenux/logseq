(ns frontend.worker.rtc.asset
  "Fns to sync assets.
  some notes:
  - has :logseq.property.asset/type, :logseq.property.asset/size, :logseq.property.asset/checksum
  - block/title, store the asset name
  - an asset-block not having :logseq.property.asset/remote-metadata
    indicates need to upload the asset to server"
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [frontend.common.missionary-util :as c.m]
            [frontend.worker.rtc.client-op :as client-op]
            [frontend.worker.rtc.log-and-state :as rtc-log-and-state]
            [frontend.worker.rtc.ws-util :as ws-util]
            [frontend.worker.state :as worker-state]
            [logseq.common.path :as path]
            [logseq.db :as ldb]
            [malli.core :as ma]
            [missionary.core :as m])
  (:import [missionary Cancelled]))

(defn- create-local-updates-check-flow
  "Return a flow that emits value if need to push local-updates"
  [repo *auto-push? interval-ms]
  (let [auto-push-flow (m/watch *auto-push?)
        clock-flow (c.m/clock interval-ms :clock)
        merge-flow (m/latest vector auto-push-flow clock-flow)]
    (m/eduction (filter first)
                (map second)
                (filter (fn [v] (when (pos? (client-op/get-unpushed-asset-ops-count repo)) v)))
                merge-flow)))

(def ^:private remote-asset-updates-schema
  [:sequential
   [:map {:closed true}
    [:op [:enum :update-asset :remove-asset]]
    [:block/uuid :uuid]
    [:malli.core/default [:map-of :keyword :any]]]])

(def ^:private *remote-asset-updates (atom nil :validator (ma/validator remote-asset-updates-schema)))
(def ^:private remote-asset-updates-flow (m/buffer 10 (m/watch *remote-asset-updates)))

(comment
  (def cancel ((m/reduce (fn [_ v] (prn :v v)) remote-asset-updates-flow) prn prn)))

(defn- remote-block-ops=>remote-asset-ops
  [db-after db-before update-ops remove-ops]
  (let [update-asset-ops
        (keep
         (fn [update-op]
           (let [block-uuid (:self update-op)
                 asset-checksum (some-> (first (:logseq.property.asset/checksum update-op))
                                        ldb/read-transit-str)]
             (when asset-checksum
               (when-let [ent (d/entity db-after [:block/uuid block-uuid])]
                 (let [local-checksum (:logseq.property.asset/checksum ent)]
                   (when (or (and local-checksum (not= local-checksum asset-checksum))
                             (nil? local-checksum))
                     (apply conj {:op :update-asset
                                  :block/uuid block-uuid}
                            (keep (fn [[k v]]
                                    (when (= "logseq.property.asset" (namespace k))
                                      [k (ldb/read-transit-str (first v))]))
                                  update-op))))))))
         update-ops)
        remove-asset-ops
        (keep
         (fn [remove-op]
           (let [block-uuid (:block-uuid remove-op)]
             (when-let [ent (d/entity db-before [:block/uuid block-uuid])]
               (when (:logseq.property.asset/checksum ent)
                 {:op :remove-asset
                  :block/uuid block-uuid}))))
         remove-ops)]
    (concat update-asset-ops remove-asset-ops)))

(defn emit-remote-asset-updates!
  [db-after db-before update-ops remove-ops]
  (when-let [asset-update-ops
             (not-empty (remote-block-ops=>remote-asset-ops db-after db-before update-ops remove-ops))]
    (reset! *remote-asset-updates asset-update-ops)))

(defn- create-mixed-flow
  "Return a flow that emits different events:
  - `:local-update-check`: event to notify check if there're some new local-updates on assets
  - `:remote-updates`: remote asset updates "
  [repo *auto-push?]
  (let [remote-update-flow (m/eduction
                            (map (fn [v] {:type :remote-updates :value v}))
                            remote-asset-updates-flow)
        local-update-check-flow (m/eduction
                                 (map (fn [v] {:type :local-update-check :value v}))
                                 (create-local-updates-check-flow repo *auto-push? 2500))]
    (c.m/mix remote-update-flow local-update-check-flow)))

(defonce ^:private *assets-sync-lock (atom nil))
(defn- holding-assets-sync-lock
  "Use this to prevent multiple assets-sync loops at same time."
  [started-dfv task]
  (m/sp
    (when-not (compare-and-set! *assets-sync-lock nil true)
      (let [e (ex-info "Must not run multiple assets-sync loops"
                       {:type :assets-sync.exception/lock-failed
                        :missionary/retry true})]
        (started-dfv e)
        (throw e)))
    (try
      (m/? task)
      (finally
        (reset! *assets-sync-lock nil)))))

(defn- clean-asset-ops!
  [repo all-asset-uuids handled-asset-uuids]
  (doseq [asset-uuid (set/difference (set all-asset-uuids) (set handled-asset-uuids))]
    (client-op/remove-asset-op repo asset-uuid)))

(defn- new-task--push-local-asset-updates
  [repo get-ws-create-task conn graph-uuid _add-log-fn]
  (m/sp
    (when-let [asset-ops (not-empty (client-op/get-all-asset-ops repo))]
      (let [upload-asset-uuids (keep
                                (fn [asset-op]
                                  (when (contains? asset-op :update-asset)
                                    (:block/uuid asset-op)))
                                asset-ops)
            asset-uuid->asset-type (into {}
                                         (keep (fn [asset-uuid]
                                                 (when-let [tp (:logseq.property.asset/type
                                                                (d/entity @conn [:block/uuid asset-uuid]))]
                                                   [asset-uuid tp])))
                                         upload-asset-uuids)
            asset-uuid->url
            (when (seq asset-uuid->asset-type)
              (->> (m/? (ws-util/send&recv get-ws-create-task
                                           {:action "get-assets-upload-urls"
                                            :graph-uuid graph-uuid
                                            :asset-uuid->metadata
                                            (into {} (map (fn [asset-uuid] [asset-uuid {"checksum" "TEST-CHECKSUM"}]))
                                                  (keys asset-uuid->asset-type))}))
                   :asset-uuid->url))]
        (prn :xxx-push-local-asset-updates asset-ops asset-uuid->url asset-uuid->asset-type)
        (doseq [[asset-uuid put-url] asset-uuid->url]
          (prn :start-upload-asset asset-uuid)
          (let [r (ldb/read-transit-str
                   (c.m/<?
                    (.rtc-upload-asset
                     ^js @worker-state/*main-thread
                     repo (str asset-uuid) (get asset-uuid->asset-type asset-uuid) put-url)))]
            (when (:ex-data r)
              (throw (ex-info "upload asset failed" r)))
            (d/transact! conn
                         [{:block/uuid asset-uuid
                           :logseq.property.asset/remote-metadata {:checksum "TEST-CHECKSUM"}}]
                         ;; Don't generate rtc ops again, (block-ops & asset-ops)
                         {:generate-asset-change-events? false
                          :persist-op? false})
            (client-op/remove-asset-op repo asset-uuid)))
        (clean-asset-ops! repo (map :block/uuid asset-ops) (keys asset-uuid->url))))))

(defn- new-task--pull-remote-asset-updates
  [repo get-ws-create-task conn graph-uuid _add-log-fn asset-update-ops]
  (m/sp
    (when (seq asset-update-ops)
      (let [update-asset-uuids (keep (fn [op]
                                       (when (= :update-asset (:op op))
                                         (:block/uuid op)))
                                     asset-update-ops)
            asset-uuid->asset-type (into {}
                                         (keep (fn [asset-uuid]
                                                 (when-let [tp (:logseq.property.asset/type
                                                                (d/entity @conn [:block/uuid asset-uuid]))]
                                                   [asset-uuid tp])))
                                         update-asset-uuids)
            asset-uuid->url
            (when (seq asset-uuid->asset-type)
              (->> (m/? (ws-util/send&recv get-ws-create-task
                                           {:action "get-assets-download-urls"
                                            :graph-uuid graph-uuid
                                            :asset-uuids (keys asset-uuid->asset-type)}))
                   :asset-uuid->url))]
        (prn :xxx-pull-remote-asset-updates asset-uuid->asset-type asset-uuid->url)
        (doseq [[asset-uuid get-url] asset-uuid->url]
          (prn :start-download-asset asset-uuid)
          (let [r (ldb/read-transit-str
                   (c.m/<?
                    (.rtc-download-asset
                     ^js @worker-state/*main-thread
                     repo (str asset-uuid) (get asset-uuid->asset-type asset-uuid) get-url)))]
            (when-let [edata (:ex-data r)]
              ;; if download-url return 404, ignore this asset
              (when (not= 404 (:status (:data edata)))
                (throw (ex-info "download asset failed" r))))))))))

(defn- get-all-asset-blocks
  [db]
  (d/q '[:find [(pull ?b [:block/uuid
                          :logseq.property.asset/type
                          :logseq.property.asset/checksum])
                ...]
         :where
         [?b :block/uuid]
         [?b :logseq.property.asset/type]]
       db))

(defn- new-task--initial-download-missing-assets
  [repo get-ws-create-task graph-uuid conn add-log-fn]
  (m/sp
    (let [local-all-asset-file-paths (ldb/read-transit-str
                                      (c.m/<? (.get-all-asset-file-paths ^js @worker-state/*main-thread repo)))
          local-all-asset-file-uuids (set (map (comp parse-uuid path/file-stem) local-all-asset-file-paths))
          local-all-asset-uuids (set (map :block/uuid (get-all-asset-blocks @conn)))]
      (when-let [asset-update-ops
                 (not-empty
                  (map (fn [asset-uuid] {:op :update-asset :block/uuid asset-uuid})
                       (set/difference local-all-asset-uuids local-all-asset-file-uuids)))]
        (m/? (new-task--pull-remote-asset-updates
              repo get-ws-create-task conn graph-uuid add-log-fn asset-update-ops))))))

(defn create-assets-sync-loop
  [repo get-ws-create-task graph-uuid conn *auto-push?]
  (let [started-dfv         (m/dfv)
        add-log-fn (fn [type message]
                     (assert (map? message) message)
                     (rtc-log-and-state/rtc-log type (assoc message :graph-uuid graph-uuid)))
        mixed-flow (create-mixed-flow repo *auto-push?)]
    {:onstarted-task started-dfv
     :assets-sync-loop-task
     (holding-assets-sync-lock
      started-dfv
      (m/sp
        (try
          (started-dfv true)
          (m/? (new-task--initial-download-missing-assets repo get-ws-create-task graph-uuid conn add-log-fn))
          (->>
           (let [event (m/?> mixed-flow)]
             (case (:type event)
               :remote-updates
               (when-let [asset-update-ops (not-empty (:value event))]
                 (m/? (new-task--pull-remote-asset-updates
                       repo get-ws-create-task conn graph-uuid add-log-fn asset-update-ops)))
               :local-update-check
               (m/? (new-task--push-local-asset-updates
                     repo get-ws-create-task conn graph-uuid add-log-fn))))
           m/ap
           (m/reduce {} nil)
           m/?)
          (catch Cancelled e
            (add-log-fn :rtc.asset.log/cancelled {})
            (throw e)))))}))

(comment
  (def x (atom 1))
  (def f (m/ap
           (let [r (m/?> (m/buffer 10 (m/watch x)))]
             (m/? (m/sleep 2000))
             r)))

  (def cancel ((m/reduce (fn [r e] (prn :e e)) f) prn prn)))
