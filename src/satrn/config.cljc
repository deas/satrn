(ns satrn.config
  (:require [clojure.string :refer [upper-case split]]
            #?(:clj [cheshire.core :as json])
            ;; [clj-yaml.core :as yaml]
            ))

(defn exit [status msg]
  (println msg)
  #?(:clj (System/exit status)
     :cljs (.exit js/process status)))

;; https://cloud.google.com/logging/docs/structured-logging : Still room to get closer to GCP
;; [yonatane.timbre-json]
(defn timbre-json-output-fn
  "timbre gcp json logging"
  [data]
  (let [{:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                timestamp_ ?line]} data
        output-data (cond->
                     {:timestamp (force timestamp_)
                      :host (force hostname_)
                      :severity (upper-case (name level))
                      :message (force msg_)}
                      (or ?ns-str ?file) (assoc :ns (or ?ns-str ?file))
                      ?line (assoc :line ?line)
                      ?err (assoc :err "No stacktrace in bb"
                                  #_(timbre/stacktrace ?err {:stacktrace-fonts {}})))]
    #?(:clj (json/generate-string output-data)
       :cljs (.stringify js/JSON (clj->js output-data)))))

(defn deep-merge
  "Merge nested maps - quick hack"
  [a b]
  (if (map? a)
    (into a (for [[k v] b] [k (deep-merge (a k) v)]))
    b))

(defn k8s-backup-resources
  [{:keys [name cloud repository location] :or {location "default"}} collections]
  {:kind "SolrBackup"
   :apiVersion "solr.apache.org/v1beta1"
   :metadata {:name name}
   :spec {:repositoryName repository
          :location location
          :recurrence {;; :maxSaved 5 ;; default
                       ;; # https://pkg.go.dev/github.com/robfig/cron/v3#section-readme
                       ;; :schedule "@daily"
                       }
          :solrCloud cloud
          :collections collections}})

#?(:cljs (def node? (resolve 'js/process)))

(def default-auth (-> (or #?(:clj (System/getenv "SOLR_AUTH")
                             :cljs (if node?
                                     (.. js/process -env -SOLR_AUTH)
                                     (.. js/__ENV -SOLR_AUTH)))
                          "solr:SolrRocks")
                      (split #":")))

(def default-base-url (or #?(:clj (System/getenv "SOLR_BASE_URL")
                             :cljs (if node?
                                     (.. js/process -env -SOLR_BASE_URL)
                                     (.. js/__ENV -SOLR_BASE_URL)))
                          "http://localhost:8983"))

(def default-solr {:base-url default-base-url
                   :basic-auth default-auth})

(def common-defaults {:destination default-solr
                      :log-level (or #?(:clj (System/getenv "LOG_LEVEL")
                                        :cljs (if node?
                                                (.. js/process -env -LOG_LEVEL)
                                                (.. js/__ENV -LOG_LEVEL)))
                                     "info")})

(def count-fields #{:update_timestamp})

(def docs-defaults (deep-merge {:query "*:*"
                                :handler "select"
                                :wrap false
                                :field-list "*"
                                :field-exclusions (into count-fields
                                                        #{:_version_
                                                          :feature.compound
                                                          :mixed.compound})
                                :request-threads 1
                                :timeout 60000
                                :batch-size 100
                                :num -1
                                :commit false
                                :ignore false
                                :error-fn exit}
                               common-defaults))

(def sync-defaults (deep-merge {:service-port 8080
                                :destination {:coll-params {;; "tlogReplicas" "3"
                                                            ;; "pullReplicas" "3"
                                                            "replicationFactor" "3" ;; NRT
                                                            "numShards" "1"}
                                              :collection-to-nodes []
                                              :remove-unused-config true}}
                               common-defaults))

(comment
  (deep-merge sync-defaults {:destination {:base-url "foo"}})
  ;; common-defaults
  sync-defaults
  )