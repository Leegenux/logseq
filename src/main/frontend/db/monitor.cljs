(ns frontend.db.monitor
  (:require [frontend.idb :as idb]
            [frontend.state :as state]
            [promesa.core :as p]
            ["/frontend/idbkv" :as idb-keyval]))

(defn- format-size
  [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (str (int (/ bytes 1024)) " KB")
    (< bytes (* 1024 1024 1024)) (str (int (/ bytes (* 1024 1024))) " MB")
    :else (str (int (/ bytes (* 1024 1024 1024))) " GB")))

(defn get-db-stats
  "获取数据库统计信息"
  []
  (p/let [main-size (when @idb/main-store (idb-keyval/estimateSize @idb/main-store))
          blocks-size (when @idb/blocks-store (idb-keyval/estimateSize @idb/blocks-store))
          files-size (when @idb/files-store (idb-keyval/estimateSize @idb/files-store))
          metadata-size (when @idb/metadata-store (idb-keyval/estimateSize @idb/metadata-store))
          
          main-keys (when @idb/main-store (idb-keyval/keys @idb/main-store))
          blocks-keys (when @idb/blocks-store (idb-keyval/keys @idb/blocks-store))
          files-keys (when @idb/files-store (idb-keyval/keys @idb/files-store))
          metadata-keys (when @idb/metadata-store (idb-keyval/keys @idb/metadata-store))]
    
    {:main {:size (format-size (or main-size 0))
            :count (count (or main-keys []))}
     :blocks {:size (format-size (or blocks-size 0))
              :count (count (or blocks-keys []))}
     :files {:size (format-size (or files-size 0))
             :count (count (or files-keys []))}
     :metadata {:size (format-size (or metadata-size 0))
                :count (count (or metadata-keys []))}
     :total {:size (format-size (+ (or main-size 0) 
                                   (or blocks-size 0) 
                                   (or files-size 0) 
                                   (or metadata-size 0)))
             :count (+ (count (or main-keys []))
                       (count (or blocks-keys []))
                       (count (or files-keys []))
                       (count (or metadata-keys [])))}}))

(defn show-db-stats
  "显示数据库统计信息"
  []
  (p/let [stats (get-db-stats)]
    (state/pub-event! [:notification/show
                       {:content [:div
                                  [:h3 "数据库统计信息"]
                                  [:table
                                   [:thead
                                    [:tr
                                     [:th "存储"]
                                     [:th "项目数"]
                                     [:th "大小"]]]
                                   [:tbody
                                    [:tr
                                     [:td "主存储"]
                                     [:td (get-in stats [:main :count])]
                                     [:td (get-in stats [:main :size])]]
                                    [:tr
                                     [:td "块存储"]
                                     [:td (get-in stats [:blocks :count])]
                                     [:td (get-in stats [:blocks :size])]]
                                    [:tr
                                     [:td "文件存储"]
                                     [:td (get-in stats [:files :count])]
                                     [:td (get-in stats [:files :size])]]
                                    [:tr
                                     [:td "元数据存储"]
                                     [:td (get-in stats [:metadata :count])]
                                     [:td (get-in stats [:metadata :size])]]
                                    [:tr
                                     [:td [:strong "总计"]]
                                     [:td [:strong (get-in stats [:total :count])]]
                                     [:td [:strong (get-in stats [:total :size])]]]]]
                                  [:div.mt-4
                                   [:button.button
                                    {:on-click #(idb/clear-idb!)}
                                    "清理数据库"]]]
                        :status :info
                        :clear? false}])))

;; 添加到设置菜单
(defn register-settings-item!
  []
  (state/pub-event! [:settings/add-item
                     {:key :database-stats
                      :title "数据库统计"
                      :desc "查看和管理数据库存储"
                      :action show-db-stats}])) 