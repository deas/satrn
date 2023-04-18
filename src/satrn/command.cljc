(ns satrn.command
  (:require
   [satrn.config :as cfg]
   #?(:clj [cheshire.core :as json])
   #?(:clj [org.httpkit.client :as http])
   #?(:cljs [goog.string :as gstring])
   #?(:cljs [goog.string.format])
   [clojure.set :refer [difference]]
   [clojure.tools.logging :refer [debugf]]
   [clojure.string :as str :refer [join split replace-first upper-case starts-with?]]))


(defn fmt [& args]
  #?(:clj (apply clojure.core/format args)
     :cljs (str "$" (apply gstring/format args))))

;; A bunch of http-request map templates
(defn cmd-cluster-status [& _]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "CLUSTERSTATUS"}})

;; Hack to get additional desired state into solr (!=git) source backend
(defn cmd-collection-meta [filename]
  {:url {:path   (str "/solr/.system/blob/" (or filename "collections-meta.json"))}
   :query-params {"wt" "filestream"}})

(defn cmd-zk-tree [path]
  {:url {:path   "/solr/admin/zookeeper"}
   :query-params {"detail" "true"
                  "path" path}})

(defn cmd-schema-fields [collection]
  {:url {:path   (str "/solr/" collection "/schema/fields")}})

(defn cmd-logging
  [& rest]
  (let [[logger level] rest]
    (cond-> {:url {:path "/solr/admin/info/logging"}}
      (and logger level) (assoc :query-params {"set" (str logger ":" level)}))))

(defn cmd-create-coll [[name cfg-name params]]
  {:url {:path   "/solr/admin/collections"}
   :query-params (merge
                  {"action" "CREATE"
                   "name" name
                   "collection.configName" cfg-name}
                  params)})

(defn cmd-modify-coll [[name cfg-name]]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "MODIFYCOLLECTION"
                  "collection" name
                  "collection.configName" cfg-name}})

(defn cmd-del-coll [name]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "DELETE"
                  "name" name}})

(defn cmd-reload-coll [name]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "RELOAD"
                  "name" name}})

(defn cmd-current-coll [name]
  {:url {:path   (str "/solr/" name "/config")}})

(defn cmd-create-alias [[alias collection]]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "CREATEALIAS"
                  "name" alias
                  "collections" collection}})

(defn cmd-del-alias [alias]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "DELETEALIAS"
                  "name" alias}})

(defn cmd-rebalance-leaders [name]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "REBALANCELEADERS"
                  "collection" name}})

(defn cmd-create-config [[name zip-is]]
  {:url {:path   "/solr/admin/configs"}
   :method :post
   :body zip-is
   :headers {"Content-Type" "application/octet-stream"}
   :query-params {"action" "UPLOAD"
                  "name" name}})

(defn cmd-del-config [name]
  {:url {:path   "/solr/admin/configs"}
   :query-params {"action" "DELETE"
                  "name" name}})

(defn cmd-del-docs [[collection query]]
  {:url {:path   (str "/solr/" collection "/update")}
   :query-params {"commitWithin" "1000"
                  "overwrite" "true"
                  "wt" "json"}
   :body (str "{'delete': {'query': '" query "'}}")
   ;;:headers {"Content-Type" "application/json"}
   })

(defn cmd-del-replica [[collection replica]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "DELETEREPLICA"
                  "shard" "shard1"
                  "collection" collection
                  "replica" replica}})

(defn cmd-add-replica [[collection create-nodeset]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "ADDREPLICA"
                  "shard" "shard1"
                  "collection" collection
                  "replica" create-nodeset}})

(defn cmd-del-backup [[collection location name]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "DELETEBACKUP"
                  "location" location
                  ;; TODO::"Exactly one of maxNumBackupPoints, purgeUnused, and backupId parameters must be provided"
                  "backupId" "0"
                  "name" name
                  "async" (fmt "%s/%s-%s" location name collection)}})

;; TODO: Appears only one collection per location? (-> async param)
(defn cmd-backup-coll [[collection location name]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "BACKUP"
                  "collection" collection
                  "location" location
                  "name" (fmt "%s-%s" name collection)
                  "async" (fmt "%s/%s-%s" location name collection)}})

(defn cmd-restore-coll [[collection location name]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "RESTORE"
                  "collection" collection
                  "location" location
                  "name" (fmt "%s-%s" name collection)
                  "async" (fmt "%s/%s-%s" location name collection)}})

(defn cmd-request-status [[request-id]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "REQUESTSTATUS"
                  "requestid" request-id}})

(defn cmd-del-status [[request-id]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "DELETESTATUS"
                  "requestid" request-id}})

(defn cmd-set-replica-leader
  [[coll-name replica]]
  {:url {:path   "/solr/admin/collections"}
   :query-params {"action" "ADDREPLICAPROP"
                  "shard" "shard1"
                  "replica" replica
                  "collection" coll-name
                  "property" "preferredLeader"
                  "property.value" "true"}})

(defn cmd-list-backup [[location name]]
  {:url {:path   (str "/solr/admin/collections")}
   :query-params {"action" "LISTBACKUP"
                  "location" location
                  "name" name}})

(defn export-symbols []
  (->> (ns-publics 'satrn.command)
       (filter #(-> % first name (starts-with? "cmd-"))) ;; Could also use meta
       (map (fn [[s v]] [(symbol "satrn.command" (name s)) @v]))
       (into {})))

(defn camel-name [^String method-name]
  (str/replace method-name #"-(\w)" #(upper-case (second %1))))

(defn ba-creds [basic-auth]
  (if (string? basic-auth)
    (split #?(:clj (System/getenv basic-auth)
              :cljs (when cfg/node? (aget js/process "env" basic-auth))) #":")
    basic-auth))

(defn init-rpc
  "Extend http request template for specific destination."
  [cmd destination]
  (let [{:keys [base-url basic-auth]} destination
        path (get-in cmd [:url :path])]
    (merge cmd {:url (str base-url path)
                ;; TODO: Quick hack to externalize auth as deep-merge does not do arrays ... yet
                :basic-auth (ba-creds basic-auth)})))

(defn- ensure-ok [response]
  (let [{:keys [status headers body error]} response]
    (if-let [errmsg (cond
                      error (str error ":" body)
                      (not= 200 status) (str status ":" body)
                      :else nil)]
      (throw #?(:clj (Exception. errmsg)
                :cljs errmsg))
      response)))

(defn json->clj [s]
  #?(:clj (json/parse-string s keyword)
     :cljs (-> (.parse js/JSON s)
               (js->clj :keywordize-keys true))))

(defn response-map
  "Create http response map for string body."
  [response]
  (json->clj (:body response)))


(def request #?(:clj #(let [res @(http/request %)]
                        (debugf "%s" {:request % :response res})
                        res) #_(comp deref http/request)))

(defn execute
  "Extend http request template for specific destination."
  ([cmd]
   (execute cmd (:destination cfg/common-defaults)))
  ([cmd destination]
   (-> cmd
       (init-rpc destination)
       request
       ensure-ok)))

(defn configs
  "Get all configs from zookeeper."
  ([] (configs cfg/default-solr))
  ([server]
   (-> (execute (cmd-zk-tree (str "/configs")) server)
       response-map
       :tree
       first
       :children)))

(defn cluster-status
  ([] (cluster-status cfg/default-solr))
  ([server]
   (-> (cmd-cluster-status)
       (execute server)
       response-map)))

(defn collections
  "Create collection map keyed by name."
  [status]
  (into {}
        (->>
         (get-in status [:cluster :collections])
         (map (fn [[k v]]
                (let [replicas (-> v :shards :shard1 :replicas)]
                  [k {:aliases (:aliases v)
                      :configName (:configName v)
                      :shards {:shard1 {:preferredLeader
                                        (some (fn [[k v]]
                                                (when (:property.preferredleader v)
                                                  (name k)))
                                              replicas)
                                        :leader
                                        (some (fn [[k v]]
                                                (when (= "true" (:leader v))
                                                  {:replica (name k)
                                                   :node_name (:node_name v)}))
                                              replicas)
                                        :replicas (->> (map (fn [[k v]] [(-> (replace-first (:node_name v) #"\..*" "")
                                                                             keyword)
                                                                         (name k)])
                                                            replicas)
                                                       (into {}))}}}]))))))

(defn live-nodes [status]
  (-> status :cluster :live_nodes))

(defn rebalance-colls
  "Get collections that need rebalancing as keywords"
  [all-colls control-coll-kws]
  (let [control-colls (select-keys all-colls control-coll-kws)
        balance-colls (filter (fn [[_ v]]
                                (> (count (filter
                                           (fn [[_ v]]
                                             ;; May also use health of shard/collection
                                             (and (= "active" (:state v))
                                                  (or (:property.preferredleader v)
                                                      (:leader v))))
                                           (-> v :shards :shard1 :replicas)))
                                   1))
                              control-colls)]
    (keys balance-colls)))

(defn collection-rule-params
  "Get parameters for collection creation"
  [coll-name rules]
  (-> (filter (fn [[c _]] (re-matches (re-pattern c) coll-name)) rules)
      first
      second))

(defn collection-node-set
  "Get node set collection creation parameter"
  [coll-name live-nodes rules]
  (let [node-pattern (-> (filter (fn [[c _]] (re-matches (re-pattern c) coll-name)) rules)
                         first
                         second)]
    (when-let [nodes (when node-pattern
                       (filter #(re-matches (re-pattern node-pattern) %) live-nodes))]
      (join "," nodes))))

;; TODO: Should probably be polymorphic
(defn config-map
  "Create filename->content map for config."
  [src configName files]
  (into []
        (->> files
             (map (fn [filename]
                    (let [file-res (execute (cmd-zk-tree (str "/configs/" configName "/" filename))
                                            src)
                          file (response-map file-res)]
                      [filename (get-in file [:znode :data])])))
             ;; TODO: Weeding out nested folders, Collection update may blow up when we have refs in config
             (filter #(second %)))))



(defn unused-configs
  ([] (unused-configs cfg/default-solr))
  ([server]
   (let [all-config-names (->> (configs server)
                               (map :text))
         used-config-names (->> (cluster-status server)
                                collections
                                (map (fn [[_ v]] (:configName v))))]
     (difference (set (filter #(not= "_default" %) all-config-names))
                 (set used-config-names)))))

(comment
  (-> (cmd-cluster-status) execute response-map)
  ;; Playground
  #_(let [cfg (load-env)
          output-fn (:output-fn cfg)]
      (resolve output-fn)))
