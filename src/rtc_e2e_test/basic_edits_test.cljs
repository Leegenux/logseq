(ns basic-edits-test
  (:require [cljs.test :as t :refer [deftest is testing]]
            [const]
            [datascript.core :as d]
            [fixture]
            [frontend.worker.rtc.client-op :as client-op]
            [frontend.worker.rtc.core :as rtc-core]
            [frontend.worker.rtc.log-and-state :as rtc-log-and-state]
            [helper]
            [logseq.outliner.batch-tx :as batch-tx]
            [meander.epsilon :as me]
            [missionary.core :as m]))

(t/use-fixtures :once
  fixture/install-some-consts
  fixture/install-example-db-fixture
  fixture/clear-test-remote-graphs-fixture
  fixture/build-two-conns-by-download-example-graph-fixture)

(defn- simplify-client-op
  [client-op]
  #_:clj-kondo/ignore
  (me/find
   client-op
    [?op-type _ {:block-uuid ?block-uuid :av-coll [[!a !v _ !add] ...]}]
    [?op-type ?block-uuid (map vector !a !v !add)]

    [?op-type _ {:block-uuid ?block-uuid}]
    [?op-type ?block-uuid]))

(def ^:private page-uuid1 (random-uuid))
(def ^:private block-uuid1 (random-uuid))

(def ^:private tx-data-map
  {:create-page
   [{:db/id "page"
     :block/name "basic-edits-test"
     :block/title "basic-edits-test"
     :block/uuid page-uuid1
     :block/created-at 1724836490809
     :block/updated-at 1724836490809
     :block/type "page"
     :block/format :markdown}
    {:block/uuid block-uuid1
     :block/updated-at 1724836490810
     :block/created-at 1724836490810
     :block/format :markdown
     :block/title "block1"
     :block/parent "page"
     :block/order "a0"
     :block/page "page"}]})

(deftest basic-edits-test
  (let [conn1 (helper/get-downloaded-test-conn)]
    (t/async
     done
     (js/Promise.
      (m/sp
        (testing "create page first"
          (let [tx-data (tx-data-map :create-page)]
            (batch-tx/with-batch-tx-mode conn1 {:e2e-test const/downloaded-test-repo}
              (d/transact! conn1 tx-data))

            (is (=
                 #{[:update-page page-uuid1]
                   [:update page-uuid1
                    [[:block/title "[\"~#'\",\"basic-edits-test\"]" true]
                     [:block/created-at "[\"~#'\",1724836490809]" true]
                     [:block/updated-at "[\"~#'\",1724836490809]" true]
                     [:block/type "[\"~#'\",\"page\"]" true]]]
                   [:move block-uuid1]
                   [:update block-uuid1
                    [[:block/updated-at "[\"~#'\",1724836490810]" true]
                     [:block/created-at "[\"~#'\",1724836490810]" true]
                     [:block/title "[\"~#'\",\"block1\"]" true]]]}
                 (set (map simplify-client-op (client-op/get-all-ops const/downloaded-test-repo)))))))

        (testing "start rtc on repo"
          (let [r (m/? (rtc-core/new-task--rtc-start const/downloaded-test-repo const/test-token))]
            (is (nil? r))))
        (testing "waiting for :create-page synced"
          (let [r (m/? (m/timeout
                        (m/reduce (fn [_ v]
                                    (when (= :rtc.log/push-local-update (:type v))
                                      (reduced v)))
                                  rtc-log-and-state/rtc-log-flow)
                        6000 :timeout))]
            (is (not= :timeout r))))

        (done))))))
