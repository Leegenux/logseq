(ns frontend.db.cleanup
  (:require [frontend.idb :as idb]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]))

;; 数据项最大保留时间（天）
(def ^:private max-retention-days 90)
;; 版本文件最大保留数量
(def ^:private max-version-files 100)
;; 清理阈值（MB）
(def ^:private cleanup-threshold-mb 500)
;; 清理后目标大小（MB）
(def ^:private target-size-mb 300)

(defn- estimate-db-size
  "估计 IndexedDB 数据库大小"
  []
  (p/let [keys (idb/get-keys)
          size-promises (map #(p/let [value (idb/get-item %)]
                                (if value
                                  (count (str value))
                                  0)) 
                             keys)
          sizes (p/all size-promises)
          total-size (reduce + 0 sizes)]
    ;; 转换为 MB
    (/ total-size (* 1024 1024))))

(defn- get-item-metadata
  "获取数据项的元数据，包括最后修改时间和大小"
  [key]
  (p/let [value (idb/get-item key)]
    (when value
      {:key key
       :size (count (str value))
       :last-modified (or (when (map? value) 
                            (or (:updated-at value) (:created-at value)))
                          (tc/to-long (t/now)))})))

(defn- should-cleanup?
  "判断是否需要清理数据库"
  []
  (p/let [size-mb (estimate-db-size)]
    (> size-mb cleanup-threshold-mb)))

(defn- get-items-to-cleanup
  "获取需要清理的数据项"
  []
  (p/let [keys (idb/get-keys)
          metadata-promises (map get-item-metadata keys)
          metadata-list (p/all metadata-promises)
          metadata-list (remove nil? metadata-list)
          
          ;; 按最后修改时间排序
          sorted-items (sort-by :last-modified metadata-list)
          
          ;; 计算需要删除多少数据以达到目标大小
          current-size-mb (/ (reduce + 0 (map :size metadata-list)) (* 1024 1024))
          size-to-remove (- current-size-mb target-size-mb)
          
          ;; 如果需要删除的大小为负数，说明不需要清理
          items-to-remove (if (<= size-to-remove 0)
                            []
                            (loop [items []
                                   remaining sorted-items
                                   removed-size 0]
                              (if (or (>= removed-size (* size-to-remove 1024 1024))
                                      (empty? remaining))
                                items
                                (recur (conj items (first remaining))
                                       (rest remaining)
                                       (+ removed-size (:size (first remaining)))))))]
    items-to-remove))

(defn cleanup!
  "清理数据库中的过期数据"
  []
  (p/let [should-clean (should-cleanup?)]
    (when should-clean
      (p/let [items-to-remove (get-items-to-cleanup)]
        (when (seq items-to-remove)
          (state/pub-event! [:notification/show 
                             {:content (str "清理数据库中的 " (count items-to-remove) " 项过期数据...")
                              :status :info
                              :clear? true}])
          (doseq [item items-to-remove]
            (idb/remove-item! (:key item)))
          (state/pub-event! [:notification/show 
                             {:content "数据库清理完成"
                              :status :success
                              :clear? true}]))))))

(defn setup-auto-cleanup!
  "设置自动清理机制"
  []
  ;; 每天检查一次
  (js/setInterval cleanup! (* 24 60 60 1000))) 