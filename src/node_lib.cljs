(ns node-lib
  (:require [clojure.string :refer [join replace-first]]
            [satrn.config :as cfg]
            [satrn.command :as cmd]
            ;; [taoensso.timbre :as timbre]
            [goog.crypt.base64 :as base64]
            ["k6/http" :as http]))


(defn request [{:keys [url query-params body basic-auth]}]
  (let [method "GET"
        query-string (->> query-params
                          (map (fn [[k v]] (str (js/encodeURIComponent k) "=" (js/encodeURIComponent v))))
                          (join "&"))
        full-url (str url "?" query-string)
        params (-> {"headers" {"Authorization" (str "Basic " (->> basic-auth
                                                                  cmd/ba-creds
                                                                  (join ":")
                                                                  base64/encodeString))}}
                   clj->js)]
    ;; http.request(method, url, body, params);
    (.request ^js http method full-url body params)
    ;; (cljs.core/clj->js [method full-url body params])
    ))


;; Retrofit glue for K6 / JavaScript
;; exposes satrn.command/cmd-* functions camel cased and using `k6/http`
;; satrn.command/cmd-cluster-status -> clusterStatus
;; exposed functions take two args, destination and parameter for wrapped cmd-*
(defn generate-exports []
  (->> (cmd/export-symbols)
       (map (fn [[s f]]
              [(-> s name (replace-first #"cmd-" "") cmd/camel-name keyword)
                         (fn [dest & args]
                           (-> args
                               cljs.core/js->clj
                               f
                               (cmd/init-rpc (js->clj dest :keywordize-keys true))
                               request))]))
       (into {:defaults cfg/common-defaults})
       clj->js))