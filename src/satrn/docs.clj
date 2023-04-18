(ns satrn.docs
  (:require [satrn.config :as cfg]
            [satrn.command :as c]
            [satrn.misc :as m]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close!]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

(defn send-batch! [dest coll-name commit timeout docs]
  (let [{:keys [base-url basic-auth]} dest]
    (-> @(http/post (str base-url "/solr/" coll-name "/update?commit=" commit)
                    {:basic-auth (c/ba-creds basic-auth)
                     :timeout timeout
                     :headers {"Content-Type" "application/json"}
                     :body (str "[" (str/join ",\n" docs) "]")})
        (merge {:doc-cnt (count docs)
                :coll-name coll-name}))))

(defn update-docs! [rdr opts]
  (let [{:keys [destination request-threads timeout batch-size ignore commit collection error-fn]} (merge cfg/docs-defaults opts)
        _ (System/setProperty "clojure.core.async.pool-size" (str (+ 1 request-threads)))
        docs-chan (chan 128)
        res-chan (chan 128)
        batch-cnt (atom nil)
        start (System/currentTimeMillis)
        stats-chan (go-loop [n-batch 0
                             q-time 0
                             docs-total 0
                             res (<! res-chan)]
                     (if res
                       (let [{:keys [error status body doc-cnt coll-name]} res]
                         (if (and (or error (not= 200 status))
                                  (not ignore))
                           (error-fn 1 (str "Got " status ", error = " error))
                           (let [header (-> (json/parse-string body true) :responseHeader)]
                             (println header)
                             (when (= (inc n-batch) @batch-cnt)
                               (close! res-chan))
                             (recur (inc n-batch)
                                    (+ q-time (:QTime header))
                                    (+ docs-total doc-cnt)
                                    (<! res-chan)))))
                       {:duration (- (System/currentTimeMillis) start)
                        :n-batch n-batch
                        :q-time q-time
                        :docs-total docs-total}))
        _ (doseq [_ (range request-threads)]
            (go-loop [{:keys [docs coll-name]} (<! docs-chan)]
              (when docs
                (let [res (send-batch! destination coll-name commit timeout docs)]
                  (>! res-chan res)
                  (recur (<! docs-chan))))))
        _ (loop [doc #_(read-line) (.readLine rdr)
                 docs (seq nil)
                 n-batch 0]
            (if doc
              (let [n-doc (.readLine rdr)
                    n-docs (cons doc docs)]
                (if (or (nil? n-doc)
                        (= batch-size (count n-docs)))
                  (do
                    (>!! docs-chan {:docs (reverse n-docs)
                                    :coll-name collection})
                    (recur n-doc (seq nil) (inc n-batch)))
                  (recur n-doc n-docs n-batch)))
              (do
                (reset! batch-cnt n-batch)
                (close! docs-chan))))]
    (<!! stats-chan)))

(defn- ensure-status [res]
  (let [{:keys [status error]} res]
    (if error
      (throw (Exception. (str "API call failed : " status " : " error)))
      res)))

(defn cmd-field-facet-counts
  [fields offset]
  (->> (map (fn [f]
              {(str "f." f ".facet.range.start") (str "NOW-" offset)
               (str "f." f ".facet.range.end") "NOW"
               (str "f." f ".facet.range.gap") (str "+" offset)}) fields)
       (reduce merge {"q" "*"
                      "facet" "true"
                      "q.op" "OR"
                      "rows" "0"
                      "defType" "lucene"
                      "df" "id"
                      "facet.range" fields})))

(defn query [cmd {:keys [destination collection handler]
                  :or {handler (:handler cfg/docs-defaults)}}]
  (let [basic-auth (:basic-auth destination)
        url (str (:base-url destination) "/solr/" collection "/" handler)
        options {:basic-auth (c/ba-creds basic-auth)
                 :headers {"Content-Type" "application/json"}
                 :query-params cmd}]
    (-> @(http/get url options)
        ensure-status
        :body
        (json/parse-string true))))

(defn doc-ratio [fields destination collection]
  (let [cmd (cmd-field-facet-counts fields "1DAY")
        all-params {;; :query "*"
                    :destination destination
                    :collection collection
                    :handler (or (:search-handler destination) "query")}
        response (query cmd all-params)
        num-found (-> response :response :numFound)
        facet-ranges (-> response :facet_counts :facet_ranges)]
    (when (> num-found 0)
      (map (fn [[k v]] [k (-> (/ (-> v :counts second) num-found)
                              float)]) facet-ranges))))

(defn export-docs! [ps {:keys [query source collection batch-size num handler field-list field-exclusions wrap]
                        :or {handler (:handler cfg/docs-defaults)
                             field-list (:field-list cfg/docs-defaults)
                             field-exclusions (:field-exclusions cfg/docs-defaults)
                             wrap (:wrap cfg/docs-defaults)}}]
  (let [;; fq, fl, df, 
        ;; sort "id asc"
        ;; TODO: Cleanup!
        url (str (:base-url source) "/solr/" collection "/" handler
                 "?fl=" (m/url-encode field-list) "&q=" (m/url-encode query))
        options {:basic-auth (c/ba-creds (:basic-auth source))
                 :headers {"Content-Type" "application/json"}}
        num-found (-> @(http/get url options)
                      ensure-status
                      :body
                      (json/parse-string true)
                      (get-in [:response :numFound]))
        num-export (if (> num -1)
                     (min num-found num)
                     num-found)
        rows (if (> num -1)
               (min num batch-size)
               batch-size)
        prn-str #(.println ps %)]
    (when wrap
      (.println ps "["))
    (doseq [start (range 0 num-export batch-size)]
      ;; (println (str url "&rows=" chunk-size "&start=" start))
      (let [docs (-> @(http/get (str url "&rows=" rows "&start=" start) options)
                     ensure-status
                     :body
                     (json/parse-string true)
                     (get-in [:response :docs]))
            doc-cnt (count docs)
            last (when (= num-export (+ start doc-cnt))
                   (- doc-cnt 1))]
        (dorun
         (map-indexed (fn [i doc] (-> (apply dissoc doc field-exclusions)
                                      json/generate-string
                                      prn-str)
                        (when (and wrap (not= i last))
                          (.println ps ",")))
                      docs))))
    (when wrap
      (.println ps "]"))))

(comment
  (require '[config.core :refer [load-env]])
  (let [{:keys [destination]} (load-env)]
    (doc-ratio (map name [:updated_timestamp]) destination "sample-1")))