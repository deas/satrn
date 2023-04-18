#!/usr/bin/env bb
(ns rpc
  (:require
   [satrn.config :as cfg]
   [satrn.command :as c]
   [taoensso.timbre :as timbre]
   [clojure.tools.logging :refer [debugf]]
   [clojure.edn :as edn]
   [babashka.curl :as curl]))

(defn execute! [request]
  (let [response (curl/request (assoc request :throw false))]
    (debugf "%s" {:request request :response response})
    response))

(defn do-rpc! [rdr destination]
  (doall (->> rdr
              edn/read
              (map #(c/init-rpc % destination))
              (map #(merge (select-keys % [:url :query-params :method])
                           (select-keys (execute! %) [:status :err]))))))

(defn -main [& _args]
  (timbre/merge-config!
   {:min-level (-> cfg/common-defaults :log-level keyword)})
  (-> (do-rpc! *in* (:destination cfg/common-defaults))
      println))

;; TODO: No *file* in k8s job
(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
