(ns frontend.idb
  "This system component provides indexedDB functionality"
  (:require ["/frontend/idbkv" :as idb-keyval :refer [Store]]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.storage :as storage]
            [goog.object :as gobj]
            [promesa.core :as p]))

;; 为不同类型的数据创建不同的存储
(defonce main-store (atom nil))
(defonce blocks-store (atom nil))
(defonce files-store (atom nil))
(defonce metadata-store (atom nil))

(defn clear-idb!
  []
  (p/do!
   (when @main-store (idb-keyval/clear @main-store))
   (when @blocks-store (idb-keyval/clear @blocks-store))
   (when @files-store (idb-keyval/clear @files-store))
   (when @metadata-store (idb-keyval/clear @metadata-store))
   (p/let [dbs (js/window.indexedDB.databases)]
     (doseq [db dbs]
       (js/window.indexedDB.deleteDatabase (gobj/get db "name"))))))

(defn clear-local-storage-and-idb!
  []
  (storage/clear)
  (clear-idb!))

;; 根据键前缀选择合适的存储
(defn- get-store-for-key
  [key]
  (cond
    (string/starts-with? (str key) "blocks/") @blocks-store
    (string/starts-with? (str key) "file/") @files-store
    (string/starts-with? (str key) "metadata/") @metadata-store
    :else @main-store))

(defn remove-item!
  [key]
  (when key
    (let [store (get-store-for-key key)]
      (when store
        (idb-keyval/del key store)))))

(defn remove-items-batch!
  [keys]
  (when (seq keys)
    (let [grouped-keys (group-by get-store-for-key keys)]
      (doseq [[store keys] grouped-keys]
        (when store
          (idb-keyval/delBatch (clj->js keys) store))))))

(defn set-item!
  [key value]
  (when key
    (let [store (get-store-for-key key)]
      (when store
        (idb-keyval/set key value store)))))

(defn get-item
  [key]
  (when key
    (let [store (get-store-for-key key)]
      (when store
        (idb-keyval/get key store)))))

(defn rename-item!
  [old-key new-key]
  (when (and old-key new-key)
    (p/let [value (get-item old-key)]
      (when value
        (set-item! new-key value)
        (remove-item! old-key)))))

(defn set-batch!
  [items]
  (when (seq items)
    (let [grouped-items (group-by #(get-store-for-key (:key %)) items)]
      (doseq [[store items] grouped-items]
        (when store
          (idb-keyval/setBatch (clj->js items) store))))))

(defn get-keys
  []
  (p/let [main-keys (when @main-store (idb-keyval/keys @main-store))
          blocks-keys (when @blocks-store (idb-keyval/keys @blocks-store))
          files-keys (when @files-store (idb-keyval/keys @files-store))
          metadata-keys (when @metadata-store (idb-keyval/keys @metadata-store))]
    (concat
     (or main-keys [])
     (or blocks-keys [])
     (or files-keys [])
     (or metadata-keys []))))

(defn get-nfs-dbs
  []
  (p/let [ks (get-keys)]
    (->> (filter (fn [k] (string/starts-with? k (str config/idb-db-prefix config/local-db-prefix))) ks)
         (map #(string/replace-first % config/idb-db-prefix "")))))

(defn clear-local-db!
  [repo]
  (when repo
    (p/let [ks (get-keys)
            ks (filter (fn [k] (string/starts-with? k (str config/local-handle "/" repo))) ks)]
      (when (seq ks)
        (remove-items-batch! ks)))))

(defn start
  "初始化所有存储"
  []
  (reset! main-store (Store. "logseq-main" "keyvaluepairs" 1))
  (reset! blocks-store (Store. "logseq-blocks" "keyvaluepairs" 1))
  (reset! files-store (Store. "logseq-files" "keyvaluepairs" 1))
  (reset! metadata-store (Store. "logseq-metadata" "keyvaluepairs" 1))

  ;; 设置自动清理
  (when-let [cleanup-fn (resolve 'frontend.db.cleanup/setup-auto-cleanup!)]
    (cleanup-fn)))
