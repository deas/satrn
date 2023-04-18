#!/usr/bin/env bb  
(ns update-docs
  (:require [satrn.config :as cfg]
            [satrn.docs :refer [update-docs!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [org.httpkit.client :as http]))

(def cli-options
  [["-d" "--destination <EDN spec>" "Dest EDN specification"
    :default (:destination cfg/docs-defaults)
    :parse-fn read-string]
   ["-r" "--request-threads n-threads" "Amount of threads doing http calls"
    :default (:request-threads cfg/docs-defaults)
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 1 % 32) "Must be a number between 1 and 32"]]
   ["-t" "--timeout milliseconds" "Timeout for http calls"
    :default (:timeout cfg/docs-defaults)
    :parse-fn #(Integer/parseInt %)]
   ["-b" "--batch-size n-docs" "Document batch size of update requests"
    :default (:batch-size cfg/docs-defaults)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 1 % 100000) "Must be a number between 1 and 100000"]]
   ["-i" "--ignore" "Ignore errors"
    :default (:ignore cfg/docs-defaults)]
   ["-c" "--commit" "Commit each update immediately"
    :default (:commit cfg/docs-defaults)]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> [""
        "Read documents from stdin and update them in collections."
        ""
        "Usage: update-docs [options] collection"
        ""
        "Options:"
        options-summary
        ""
        ]
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
      {:options (merge options {:collection (first arguments)
                                :error-fn cfg/exit})}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (cfg/exit (if ok? 0 1) exit-message)
      (println (update-docs! *in* options)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (read-string "{:base-url \"http://localhost:8983\", :basic-auth [\"solr\" \"\"]}")
  
  @(http/post "http://batman:18983/solr"
              {:timeout 5000
               :headers {"Content-Type" "application/json"}
                     ;; :body (str "[" (str/join ",\n" docs) "]")
               })
  (let [filename "./stuff.json"
        docs (with-open [rdr (io/reader filename)]
               (update-docs! (java.io.BufferedReader. rdr)
                             {:collections ["sample"]
                              :destination nil
                              :batch-size 100
                              :request-threads 1}))]
    docs)
  )
