#!/usr/bin/env bb
(ns cnt-qdff
  (:require
   [satrn.misc :as m]
   [satrn.config :as cfg]
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   [clojure.string :refer [join split]]
   [clojure.pprint :refer [pprint]]
   [clojure.tools.cli :refer [parse-opts]]))
               
(def cli-options
  [["-h" "--help"]
   ["-a" "--auth <basic_auth>" "Basic Auth"
    :default cfg/default-auth
    :parse-fn #(split % #":")]
   ["-1" "--base-1 <base-1-url>" "URL base 1"]
   ["-2" "--base-2 <base-2-url>" "URL base 2"]])

(defn usage [options-summary]
  (->> [""
        "Usage: cnt-qdff -a username:password -1 ... -2 ... query_1 ... query_n"
        ""
        "Options:"
        options-summary
        ""
        "Compare counts for queries"
        ""]
       (join \newline)))


(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn- result-count[base auth q]
  (-> @(http/get (str base "?rows=0&start=0&q=" q)
                 {:basic-auth auth})
      :body
      (json/parse-string keyword)
      (get-in [:response :numFound])))

(defn cnt-qdiff [base-1 base-2 auth query]
  (let [cnt-1 (result-count base-1 auth query)
        cnt-2 (result-count base-2 auth query)]
    [query cnt-1 cnt-2
     (when (< 0 cnt-2)
       (->> (/ cnt-1 cnt-2)
            float
            (format "%.2f")))]))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (and (< 0 (count arguments)) (:base-1 options) (:base-2 options))
      {:action #(map
                 (partial cnt-qdiff (:base-1 options)
                          (:base-2 options)
                          (:auth options))
                 (->> arguments (map m/url-encode)))}
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
  (let [base-1 "http://solr-1:8983/solr/sample-1/search"
        base-2 "http://solr-2:8983/solr/sample-1/search"
        auth (split "admin:..." #":")
        query "iphone"]
    (cnt-qdiff base-1 base-2 auth query))
  )

