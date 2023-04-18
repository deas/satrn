#!/usr/bin/env bb
(ns logs
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.time OffsetDateTime]
           [java.time.format DateTimeFormatter]
           ;; import java.util.Locale)
           )
  )

;; Parameters
#_(def auth ["admin"
           (:out (shell/sh "gcloud" "--project=abcd" "..."))])

#_(
gcloud --project=abcd logging read 'resource.type= "k8s_container"
resource.labels.cluster_name= "search-cluster"
resource.labels.namespace_name= "search"
resource.labels.container_name= "solrcloud-node"
severity>=WARNING
labels.collection= "c:sample-1"
' --limit 10 --order asc --format json
;; timestamp<="2015-05-31T23:59:59Z" AND timestamp>="2015-05-31T00:00:00Z"
   )

(defn gcp-log-entry [{:keys [jsonPayload labels] :as log}]
  (merge (select-keys log [:severity])
         (select-keys jsonPayload [:message :timestamp :exception :logName :source_host])
         (select-keys labels [:collection :replica :compute.googleapis.com/resource_name #_:core])))

(defn format-date [ts-string]
  (let [dt (OffsetDateTime/parse ts-string)]
    (.format dt DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

(defn log-string [{:keys [timestamp severity message exception logName source_host collection replica] :as mapped_log}]
  (format "%s %-8s %s %-14s %s %s %s %s"
          ;; "%s %-8s %s %-22s %-14s %s %s %s %s"
          (format-date timestamp)
          severity
          #_(->
             (:compute.googleapis.com/resource_name mapped_log)
             (str/replace-first #".*-pool-" "pool-"))
          source_host
          collection
          replica
          (str/replace-first logName #".*\." "")
          message
          (if exception
            (str "💥 " (:exception_message exception) " 💥")
            "")))

(defn -main [& args]
  (let [;; {:keys [options exit-message ok?]} (validate-args args)
        logs-in (json/parse-stream *in* keyword)
        fmt-logs (->> logs-in
                      (map gcp-log-entry)
                      (map log-string))]
    (doall (map println fmt-logs))
    #_(if exit-message
        (cfg/exit (if ok? 0 1) exit-message)
        (println (update-docs! *in* options)))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (let [logs-in (-> (slurp (io/resource "logs.json"))
                    (json/parse-string keyword))
        log-entries (map gcp-log-entry logs-in)
        fmt-logs (map log-string log-entries)]
    fmt-logs)

  (let [res (io/resource "logs-stream.json")
        stream (io/input-stream res)
        fmt-logs (with-open [rdr (io/reader stream)]
                   (->> (-> (java.io.BufferedReader. rdr)
                            (json/parsed-seq keyword))
                        (map gcp-log-entry)
                        (map log-string)
                        (into [])))]
    fmt-logs)
  )
  