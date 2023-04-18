#!/usr/bin/env bb  
(ns clone
  (:require [satrn.config :as cfg]
            [satrn.docs :refer [update-docs! export-docs!]]
            [config.core :refer [load-env]]
            [clojure.tools.logging :refer [info infof error errorf warn warnf]]
            [clojure.core.async :refer [thread]]
            ;; [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

(def cli-options
  [#_["-d" "--destination <EDN spec>" "Dest EDN specification"
      :default (:destination cfg/defaults)
      :parse-fn read-string]
   ["-r" "--request-threads n-threads" "Amount of threads doing http calls"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 1 % 32) "Must be a number between 1 and 32"]]
   ["-t" "--timeout milliseconds" "Timeout for http calls"
    :default 60000
    :parse-fn #(Integer/parseInt %)]
   ["-b" "--batch-size n-docs" "Document batch size of update requests"
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 1 % 100000) "Must be a number between 1 and 100000"]]
   ["-i" "--ignore" "Ignore errors"
    :default false]
   ["-c" "--commit" "Commit each update immediately"
    :default false]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> [""
        "Update documents from source- to destination Solr."
        ""
        "Usage: clone [options]"
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
      ;; (>= (count arguments) 1)
      :else
      {:options (merge options {:error-fn cfg/exit})}
      ;; :else ; failed custom validation => exit with usage summary
      #_{:exit-message (usage summary)})))


;; TODO: Pipe size should probably be bigger
(defn clone! [options destination source]
  (doall (map (fn [coll]
                (let [collection (name coll)
                      pis (java.io.PipedInputStream.)
                      pos (java.io.PipedOutputStream. pis)
                      update-opts (merge options {:destination (str destination)
                                                  :collection collection})
                      export-opts (merge options {:source (str source)
                                                  :collection collection
                                                  :batch-size 100
                                                  :num -1})]
                  (thread
                    (update-docs!  (-> (java.io.InputStreamReader. pis) java.io.BufferedReader.) update-opts))
                  (export-docs! (java.io.PrintStream. pos) export-opts)))
              (:collections source))))

(defn clone-all!
  "Clone all collections to destination"
  [{:keys [sources destination] :as options}]
  (infof "Cloning %d sources to %s" (count sources) (:base-url destination))
  (doall (map (partial clone! options destination)
              sources)))

(defn -main [& args]
  (let [cfg (load-env)
        {:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (cfg/exit (if ok? 0 1) exit-message)
      (println (clone-all! (merge cfg options))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment)
