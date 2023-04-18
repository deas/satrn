#!/usr/bin/env bb
(ns dff
  (:require
   [clojure.data :refer [diff]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [clojure.string :refer [join]]
   ;; [clojure.set :refer [difference]]
   [clojure.pprint :refer [pprint]]
   [clojure.tools.cli :refer [parse-opts]]))
               
(def cli-options
  [["-h" "--help"]
   ["-p" "--prefix-ids"]])

(defn usage [options-summary]
  (->> [""
        "Usage: dff file1.json file2.json"
        ""
        "Options:"
        options-summary
        ""
        "Map JSON files to Clojure and diff (https://clojuredocs.org/clojure.data/diff)"
        "Drop common bit and pretty print."
        ""
        "Example:"
        ""
        "export_docs.clj -H search -s '{:base-url \"http://solr-1:8983/\", :basic-auth [\"solr\" \"'$PASS'\"]}' -q \"schall\" -w sample-1 >docs-1.json"
        "export_docs.clj -H search -s '{:base-url \"https://solr-2:8983\", :basic-auth [\"solr\" \"'$PASS'\"]}' -q \"schall\" -w sample-1 > docs-2.json"
        "dff.clj -p docs-1.json docs-2.json"
        ""]
       (join \newline)))


(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn prefix-id [coll pref]
  (map (fn [{:keys [id] :as v}]
         (assoc v :id (str pref id)))
       coll))

(defn json-diff
  ([file1 file2]
   (json-diff file1 file2 false))
  ([file1 file2 prefix]
   (diff (cond-> (-> (io/reader file1) (json/parse-stream keyword))
           prefix (prefix-id "1-"))
         (cond-> (-> (io/reader file2) (json/parse-stream keyword))
           prefix (prefix-id "2-")))))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (= 2 (count arguments))
      {:action (comp pop (partial json-diff (first arguments) (second arguments) (:prefix-ids options)))}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (pprint (action)))))

;; TODO: No *file* in k8s job
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (pop (json-diff "test/1.json" "test/2.json" true))
  )