(ns frontend.db.rtc.full-upload-download-graph
  "- upload local graph to remote
  - download remote graph"
  (:require-macros [frontend.db.rtc.macro :refer [with-sub-data-from-ws get-req-id get-result-ch]])
  (:require [frontend.db.conn :as conn]
            [datascript.core :as d]
            [frontend.db.rtc.ws :refer [<send!]]
            [frontend.state :as state]
            [cljs.core.async :as async :refer [chan go <!]]
            [cljs.core.async.interop :refer [p->c]]
            [cljs-http.client :as http]
            [cognitect.transit :as transit]
            [logseq.db.schema :as db-schema]
            [logseq.db.sqlite.util :as sqlite-util]
            [frontend.persist-db :as persist-db]
            [frontend.db.rtc.op :as op]
            [logseq.outliner.pipeline :as outliner-pipeline]))


(defn- export-as-blocks
  [repo]
  (let [db (conn/get-db repo)
        datoms (d/datoms db :eavt)]
    (->> datoms
         (partition-by :e)
         (keep (fn [datoms]
                 (when (seq datoms)
                   (reduce
                    (fn [r datom]
                      (when (and (contains? #{:block/parent :block/left} (:a datom))
                                 (not (pos-int? (:v datom))))
                        (throw (ex-info "invalid block data" {:datom datom})))
                      (assoc r (:a datom) (:v datom)))
                    {:db/id (:e (first datoms))}
                    datoms)))))))

(defn <upload-graph
  "Upload current repo to remote, return remote {:req-id xxx :graph-uuid <new-remote-graph-uuid>}"
  [state repo]
  (go
    (let [{:keys [url key all-blocks-str]}
          (with-sub-data-from-ws state
            (<! (<send! state {:req-id (get-req-id) :action "presign-put-temp-s3-obj"}))
            (let [all-blocks (export-as-blocks repo)
                  all-blocks-str (transit/write (transit/writer :json) all-blocks)]
              (merge (<! (get-result-ch)) {:all-blocks-str all-blocks-str})))]
      (<! (http/put url {:body all-blocks-str}))
      (with-sub-data-from-ws state
        (<! (<send! state {:req-id (get-req-id) :action "full-upload-graph" :s3-key key}))
        (let [r (<! (get-result-ch))]
          (if-not (:graph-uuid r)
            (ex-info "upload graph failed" r)
            (do (<! (p->c (op/<update-graph-uuid! repo (:graph-uuid r))))
                r)))))))

(def block-type-ident->str
  {:block-type/property   "property"
   :block-type/class      "class"
   :block-type/whiteboard "whiteboard"
   :block-type/macros     "macros"
   :block-type/object     "object"})


(defn- replace-db-id-with-temp-id
  [blocks]
  (mapv
   (fn [block]
     (let [db-id (:db/id block)
           block-parent (:db/id (:block/parent block))
           block-left (:db/id (:block/left block))
           block-alias (map :db/id (:block/alias block))
           block-tags (map :db/id (:block/tags block))
           block-type (keep (comp block-type-ident->str :db/ident) (:block/type block))]
       ;; TODO: :block/tags :block/type
       (cond-> (assoc block :db/id (str db-id))
         block-parent (assoc :block/parent (str block-parent))
         block-left (assoc :block/left (str block-left))
         (seq block-alias) (assoc :block/alias (map str block-alias))
         (seq block-tags)  (assoc :block/tags (map str block-tags))
         (seq block-type)  (assoc :block/type block-type))))
   blocks))

(def page-of-block
  (memoize
   (fn [id->block-map block]
     (when-let [parent-id (:block/parent block)]
       (when-let [parent (id->block-map parent-id)]
         (if (:block/name parent)
           parent
           (page-of-block id->block-map parent)))))))

(defn- fill-block-fields
  [blocks]
  (let [groups (group-by #(boolean (:block/name %)) blocks)
        ;; _page-blocks (get groups true)
        other-blocks (set (get groups false))
        id->block (into {} (map (juxt :db/id identity) blocks))
        block-id->page-id (into {} (map (fn [b] [(:db/id b) (:db/id (page-of-block id->block b))]) other-blocks))]
    (mapv (fn [b]
            (let [b (assoc b :block/format :markdown)]
              (if-let [page-id (block-id->page-id (:db/id b))]
                (assoc b :block/page page-id)
                b)))
          blocks)))


(defn- <transact-remote-all-blocks-to-sqlite
  [all-blocks repo]
  (go
    (let [{:keys [t blocks]} all-blocks
          conn (d/create-conn db-schema/schema-for-db-based-graph)
          blocks* (replace-db-id-with-temp-id blocks)
          blocks-with-page-id (fill-block-fields blocks*)]
      (d/transact! conn blocks-with-page-id)
      (let [db (d/db conn)
            blocks*
            (d/pull-many db '[*] (keep (fn [b] (when-let [uuid (:block/uuid b)] [:block/uuid uuid])) blocks))
            blocks** (outliner-pipeline/build-upsert-blocks blocks* nil db)]
        (<! (p->c (persist-db/<new repo)))
        (<! (persist-db/<transact-data repo blocks** nil))
        (<! (p->c (op/<update-local-tx! repo t)))))))


(defn <download-graph
  [state repo graph-uuid]
  (go
    (let [{:keys [url]}
          (with-sub-data-from-ws state
            (<send! state {:req-id (get-req-id) :action "full-download-graph" :graph-uuid graph-uuid})
            (<! (get-result-ch)))
          {:keys [status body] :as r} (<! (http/get url))
          repo (str "logseq_db_rtc-" repo)]
      (if (not= 200 status)
        (ex-info "<download-graph failed" r)
        (let [reader (transit/reader :json)
              all-blocks (transit/read reader body)]
          (<! (<transact-remote-all-blocks-to-sqlite all-blocks repo))
          (<! (p->c (op/<update-graph-uuid! repo graph-uuid))))))))

(comment
  )
