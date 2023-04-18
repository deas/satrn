(ns satrn-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [config.core :refer [load-env]]
            [satrn :as s]
            [satrn.serve :as srv :refer :all]
            [satrn.config :as cfg]
            [satrn.command :as c :refer [response-map collection-node-set init-rpc request]]))


(defn base-url-file []
  (str "file://"
       (-> (.getParent (io/as-file (.getPath (io/resource "config.edn")))))
       java.io.File/separator
       "config"))

(defn mock-init-rpc [cmd _] cmd)

(defn mock-request [opts]
  #_(pprint opts)
  (let [path (get-in opts [:query-params "path"])
        action (get-in opts [:query-params "action"])]
    (cond
      (= path "/configs") ;; "is zk"
      {:status 200 :body (slurp (io/resource "zookeeper-configs-8-11.json"))}
      (= action "CLUSTERSTATUS")
      {:status 200 :body (slurp (io/resource "clusterstatus-8-11.json"))}
      :else
      {:status 200 :body "{\"\"}"})))

(deftest core-tests
  (testing "cljc"
    (is (= (c/fmt "%s-%s" "a" "b") "a-b"))))

(deftest random-tests
  (srv/init! (load-env))
  (testing "Incrementing sample-1 updates metrics"
    (is (true? (= 1 (-> (srv/inc-coll-total :sample-1 :updates)
                        :collections
                        :events
                        :sample-1
                        :updates)))))
  (testing "Deep merge"
    (let [m (-> (cfg/deep-merge {:a {:b {:c 1
                                         :d 2}}}
                                {:a {:b {:d 3
                                         :e 4}}})
                :a
                :b)]
      (is (true? (= 3 (:d m))))
      (is (true? (= 4 (:e m)))))))

(deftest collection-tests
  (with-redefs [;; sync-source (fn [_ _] nil)
                request mock-request
                init-rpc mock-init-rpc]
    (testing "Config sample-2-abcdef is unused"
      (is (true? (contains? (c/unused-configs)
                            "sample-2-abcdef"))))
    (testing "Collection sample-2 exists"
      (is (true? (contains? (-> (c/cluster-status)
                                c/collections)
                            :sample-2))))
    (testing "No collections unbalanced"
      (let [all-collections (-> (c/cluster-status)
                                c/collections)]
        (is (true? (= 0 (count (c/rebalance-colls all-collections (srv/init! (load-env)))))))))
    (testing "Collection to nodeset")
    (is (let [coll-name "sample-1-foo" ;; TODO: Should check for expected nodes
              live-nodes (-> (c/cluster-status)
                             (get-in [:cluster :live_nodes]))
              rules [[".*sample-2.*" ".*-[12]\\..*"]
                     [".*sample-1.*" ".*-[34]\\..*"]]]
          (collection-node-set coll-name live-nodes rules)))))

(comment ;; Scratchpad
  (let [dest {:base-url "http://localhost:8983"
              :basic-auth ["solr" ""]}
        all-collections (-> (c/cluster-status dest)
                            ;; (slurp (io/resource "clusterstatus-p.json")) (json/parse-string keyword)
                            c/collections)]
    ;; (c/rebalance-colls all-collections (srv/init! (load-env)))
    (->> all-collections
         (map (fn [[k v]] [k
                           (merge {:shards {:shard1 {:preferredLeader nil}}}
                                  (select-keys v [:aliases]))]))
         (into {})
         json/generate-string
         (spit "/tmp/collections-meta.json")))

  #_(cfg/deep-merge {:a {:b {:c 1
                             :d 2}}}
                    {:a {:b {:d 3
                             :e 4}}})

  (re-matches #"^/(sample-1|sample-2)(.*)" "/sample-1de-foo")
  (re-matches #"^/(sample-1|sample-2)((?!.*raw).*)" "/sample-1de-foo-raw")

  ;; curl -f -u ${SOLR_AUTH} -X POST -H 'Content-Type: application/json' -d @collections-meta.json "$SOLR_BASE_URL/solr/.system/blob/collections-meta.json"
  ;; curl -f -u ${SOLR_AUTH} "$SOLR_BASE_URL/solr/.system/blob/collections-meta.json?wt=filestream"
  (let [src {:base-url (base-url-file)}
        source-colls (s/source-colls src)
        config-name (-> source-colls first second :configName)]
    source-colls
    ;; (s/config-files src config-name)
    )


  (with-redefs [request mock-request
                init-rpc mock-init-rpc])

  #_(let [collections (-> (slurp (io/resource "clusterstatus-replicas-down.json"))
                          (json/parse-string keyword)
                          (get-in [:cluster :collections]))
          recreate-params (map (fn [[k v]]
                                 [(name k)
                                  (->> v
                                       :shards
                                       :shard1
                                       :replicas
                                       (filter (fn [[_ v]] (= "down" (:state v))))
                                       (map (fn [[k v]] [(name k) (:node_name v)])))])
                               collections)
          recreate-cmds (->> (map (fn [[c rcs]]
                                    (map (fn [r]
                                           [(c/cmd-del-replica [c (first r)])
                                            (c/cmd-add-replica [c (second r)])])
                                         rcs))
                                  recreate-params)
                             (apply concat)
                             (apply concat))]
      (spit "/tmp/rpc-recreate.replicas.edn" (pr-str recreate-cmds)))

  #_(defn rpc-set-replica-leader
      [coll-name replica]
      {:url {:path   "/solr/admin/collections"}
       :query-params {"action" "ADDREPLICAPROP"
                      "shard" "shard1"
                      "replica" replica
                      "collection" coll-name
                      "property" "preferredLeader"
                      "property.value" "true"}})

  ;; TODO: Dirty!
  #_(defn rpc-move
      [coll-name replica target-node]
      {:url {:path   "/solr/admin/collections"}
       :query-params {"action" "MOVEREPLICA"
                      "collection" (name coll-name)
                      "targetNode" target-node
                      "replica" replica}})

  #_(defn delete-replica
      [coll-name shard replica]
      {:url {:path   (str "/solr/admin/collections")}
       :query-params {"action" "DELETEREPLICA"
                      "shard" shard
                      "collection" coll-name
                      "replica" replica}})



;; Leave only one
  #_(defn drop-replicas
      [[coll-name-kw coll]]
      (let [;; [coll-name-kw coll] (first collections)
            drop-replicas (-> (get-in coll [:shards :shard1 :replicas])
                              rest)
            drop-names (map (fn [[k _]] [(name coll-name-kw) "shard1" (name k)]) drop-replicas)]
        (into [] (map #(apply delete-replica %) drop-names))))

  ;; Remove all but one (raw) replica
  #_(let [;; sample-2 012, sample-1 345
          collections (-> (slurp (io/resource "clusterstatus-p.json"))
                          (json/parse-string keyword)
                          (get-in [:cluster :collections]))
          drop-collections (filter (fn [[k _]] (re-matches #".*-raw" (name k))) collections)
          drops (flatten (map #(drop-replicas %) drop-collections))]
      (spit "/tmp/rpc-drop.edn" (pr-str drops)))


  ;; Move single raw replica to proper node
  #_(let [collections (-> (slurp (io/resource "clusterstatus-p.json"))
                          (json/parse-string keyword)
                          (get-in [:cluster :collections]))
          move-collections (filter (fn [[k _]] (re-matches #".*-raw" (name k))) collections)
          move-replicas (map (fn [[k v]]
                               (let [coll-name (name k)]
                                 [coll-name
                                  (-> (get-in v [:shards :shard1 :replicas])
                                      first
                                      first
                                      name)
                                  (if (re-matches #"sample-2.*-raw" coll-name)
                                    "a-int-solrcloud-0.a-int-solrcloud-headless.search:8983_solr"
                                    "a-int-solrcloud-1.a-int-solrcloud-headless.search:8983_solr")])) move-collections)
          moves (flatten (map #(apply rpc-move %) move-replicas))]
      (spit "/tmp/rpc-move-raw.edn" (pr-str moves)))

  ;; Move sample-2/sample-1 replicas to proper node
  #_(let [;; sample-2 012, sample-1 345
          hosts-sample-1 #{"a-solrcloud-3.a-solrcloud-headless.search:8983_solr"
                         "a-solrcloud-4.a-solrcloud-headless.search:8983_solr"
                         "a-solrcloud-5.a-solrcloud-headless.search:8983_solr"}
          hosts-sample-2 #{"a-solrcloud-0.a-solrcloud-headless.search:8983_solr"
                        "a-solrcloud-1.a-solrcloud-headless.search:8983_solr"
                        "a-solrcloud-2.a-solrcloud-headless.search:8983_solr"}
          collections (-> (slurp (io/resource "clusterstatus-p.json"))
                          (json/parse-string keyword)
                          (get-in [:cluster :collections]))
          move-collections (filter (fn [[k _]] (re-matches #".*(sample-1|sample-2).*" (name k))) collections)

          moves (flatten (map #(move-replicas hosts-sample-1 hosts-sample-2 %) move-collections))]
      (spit "/tmp/rpc-move.edn" (pr-str moves))))