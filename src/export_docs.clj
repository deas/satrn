#!/usr/bin/env bb
(ns export-docs
  (:require [satrn.config :as cfg]
            [satrn.docs :refer [export-docs!]]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [org.httpkit.client :as http]))

(def cli-options
  [["-s" "--source <EDN spec>" "Source EDN specification"
    :default (:destination cfg/docs-defaults)
    :parse-fn read-string]
   ["-q" "--query <query>" "The query"
    :default (:query cfg/docs-defaults)]
   ["-w" "--wrap" "Wrap json export as array"
    :default (:wrap cfg/docs-defaults)]
   ["-H" "--handler <handler>" "The handler"
    :default (:handler cfg/docs-defaults)]
   ["-c" "--batch-size n-docs" "Document batch size"
    :default (:batch-size cfg/docs-defaults)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 1 % 100000) "Must be a number between 1 and 100000"]]
   ["-n" "--num n-docs" "Docs to export"
    :default (:num cfg/docs-defaults)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< -1 % 100000) "Must be a number between -1 and 100000"]]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> [""
        "Export documents from collections and write them to stdout."
        ""
        "Usage: export-docs [options] collection"
        ""
        "Options:"
        options-summary
        ""]
       (str/join \newline)))


(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (= (count arguments) 1)
      {:options (merge options {:collection (first arguments)})}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))


(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (cfg/exit (if ok? 0 1) exit-message)
      ;; TODO: cleanup/unify
      (export-docs! System/out options))))

;; TODO: No *file* in k8s job
#_(when collection
  (-main [collection]))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  

  @(http/get "http://localhost:8983/solr/sample-1/select?fl=*&q=*:*"
             {:basic-auth ["solr" "SolrRocks"]
              :headers {"Content-Type" "application/json"}})
  (let [coll-name "sample-1"
        batch-size 1]
    (export-docs! System/out {:source nil
                              :collection coll-name
                              :batch-size batch-size
                              :num -1})))
