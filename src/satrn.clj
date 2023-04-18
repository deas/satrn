#!/usr/bin/env bb
(ns satrn
  (:import (java.util.zip ZipOutputStream ZipEntry)
           (java.io ByteArrayOutputStream ByteArrayInputStream))
  (:require
   [satrn.config :as cfg]
   [satrn.misc :as m]
   [satrn.serve :as srv]
   [satrn.command :as c :refer [response-map]]
   [clojure.tools.logging :refer [info infof errorf]]
   [taoensso.timbre :as timbre]
   [cheshire.core :as json]
   ;; [cli-matic.core :refer [run-cmd]]
   [digest]
   [clojure.data :refer [diff]]
   [clojure.string :refer [join starts-with?]]
   [clojure.java.io :as io]
   [clojure.tools.cli :refer [parse-opts]]
   [config.core :refer [load-env]]))

(def timbre-json-output-fn cfg/timbre-json-output-fn)

(def config (atom nil))

(defn file-source? [src & _] (starts-with? (:base-url src) "file:/"))

(defmulti collection-meta file-source?)

(defmethod collection-meta false
  [src collection]
  (try
    (-> (c/cmd-collection-meta (:meta-file src))
        (c/execute src)
        :body
        io/reader
        (json/parse-stream keyword)
        collection)
    (catch Exception _ (infof "No metadata for source %s" (:base-url  src)))))

(defmethod collection-meta true
  [src collection]
  (let [root-path (-> src :base-url io/as-url .getFile)
        meta-file (io/as-file (str root-path java.io.File/separator (name collection) ".json"))]
    (when (.isFile meta-file)
      (-> (slurp meta-file) (json/parse-string keyword)))))


(defmulti config-files file-source?)

(defmethod config-files false
  [src config-name]
  (->> (c/execute (c/cmd-zk-tree (str "/configs/" config-name)) src)
       response-map
       :tree
       first
       :children
       (map
        #(if-let [data (:data %)]
           (:title data) ;; Solr 8.3.1
           (:text %))) ;;  Solr 8.9
       sort
       (c/config-map src config-name)))

(defmethod config-files true
  [src config-name]
  (let [root-dir (-> (str (:base-url src) java.io.File/separator config-name) io/as-url .getFile)
        files (-> root-dir io/as-file .list seq)]
    (->> files
         (map #(-> (str root-dir java.io.File/separator %) io/as-file))
         (filter #(.isFile %))
         (map #(.getName %))
         sort
         (map (fn [n] [n (-> (str root-dir java.io.File/separator n) slurp)]))
         (into []))))


(defn zip-config
  "Write files zip to output steam"
  [files os]
  (with-open [zip (ZipOutputStream. os)]
    (doall
     (map (fn [[file content]]
            (let [_ (info "Adding" file)
                  entry (ZipEntry. file)]
              (doto zip
                (.putNextEntry entry)
                (.write (.getBytes content))
                (.closeEntry))))
          files))
    zip))

(defn version
  "Compute version from files."
  [files]
  (let [os (ByteArrayOutputStream.)]
    (doall (map (fn [[filename content]]
                  (when content
                    (io/copy filename os)
                    (io/copy content os)))
                files))
    (-> (.toByteArray os)
        ByteArrayInputStream.
        digest/sha-1
        (subs 0 7))))

;; Need to query zookeeper as we might create it during the iteration
(defn cfg-exists
  "Test if config exists in zookeeper."
  [cfg-name dest]
  (some #(= cfg-name (:text %))
        (c/configs dest)))

(defn sync-config
  "Sync single config."
  [new-dest-cfg-name files dest]
  (if (cfg-exists new-dest-cfg-name dest)
    (info "Config " new-dest-cfg-name "exists")
    (let [_ (info "Creating config" new-dest-cfg-name "on" (:base-url dest))
          os (ByteArrayOutputStream.)
          _ (zip-config files os)]
      (-> [new-dest-cfg-name (ByteArrayInputStream. (.toByteArray os))]
          c/cmd-create-config
          (c/execute dest)))))

(defn sync-collection
  "Sync only collection. Create with config if not exits, modify if outdated."
  [coll-name-kw dest-cfg-name dest-colls dest live-nodes]
  (let [coll-name (name coll-name-kw)]
    (if-let [dest-coll (coll-name-kw dest-colls)]
      (if (not= dest-cfg-name (:configName dest-coll))
        (let [_ (info "Updating config for" coll-name " to " dest-cfg-name)]
        ;; TODO: Core reload after update?
          (-> [coll-name dest-cfg-name]
              c/cmd-modify-coll
              (c/execute dest))
          (srv/inc-coll-total coll-name-kw :updates)
          (-> coll-name
              c/cmd-reload-coll
              (c/execute dest)))
        (info "Collection" coll-name "already has up to date config" dest-cfg-name "on" (:base-url dest)))
      (let [create-node-set (c/collection-node-set coll-name live-nodes (:collection-to-nodes dest))
            coll-rule-params (c/collection-rule-params coll-name (:collection-to-params dest))
            coll-params (cond-> (:coll-params dest)
                          (seq create-node-set) (assoc "createNodeSet" create-node-set)
                          coll-rule-params (merge coll-rule-params))
            _ (info "Creating collection" coll-name "with config" dest-cfg-name "on" (:base-url dest) "with params" coll-params)]
        (-> [coll-name dest-cfg-name coll-params]
            c/cmd-create-coll
            (c/execute dest))
        (srv/inc-coll-total coll-name-kw :updates)))))

(defn sync-aliases
  "Sync aliases for a single collection"
  [src-coll-name src-coll dst-coll-name dest-coll dest dest-prefix]
  (let [[create-aliases del-aliases _] (diff (into #{} (->> (:aliases src-coll)
                                                            (map #(str dest-prefix %))))
                                             (into #{} (->> (:aliases dest-coll)
                                                            (map name))))]
    (doall (map (fn [alias]
                  (info "Creating alias" alias "for collection" (name src-coll-name) "on" (:base-url dest))
                  (-> [alias (name dst-coll-name)]
                      c/cmd-create-alias
                      (c/execute dest)))
                create-aliases))
    (doall (map (fn [alias]
                  (info "Remove alias" alias "on" (:base-url dest))
                  (-> alias
                      c/cmd-del-alias
                      (c/execute dest)))
                del-aliases))))

(defn sync-meta
  "Sync metadata for a single collection"
  [dest-coll-name dest-colls coll-meta dest]
  (if-let [src-leader-node (-> coll-meta :shards :shard1 :preferredLeader)]
    (let [shard (-> dest-colls dest-coll-name :shards :shard1)
          {:keys [leader preferredLeader]} shard ;; actual
          wanted-shard ((keyword src-leader-node) (-> shard :replicas))]
      (when-not (= preferredLeader wanted-shard)
        (infof "Setting replica leader for collection %s to %s"
               (name dest-coll-name)
               src-leader-node)
        (-> [(name dest-coll-name) (name wanted-shard)]
            c/cmd-set-replica-leader
            (c/execute dest)))
      (if (starts-with? (:node_name leader) src-leader-node)
        (infof "Replica leader for collection %s already set to %s"
               (name dest-coll-name)
               src-leader-node)
        (do
          (infof "Rebalance leader for %s on %s " (name dest-coll-name) (:base-url dest))
          (-> (name dest-coll-name)
              c/cmd-rebalance-leaders
              (c/execute dest))
          (srv/inc-coll-total dest-coll-name :rebalances))))
    (infof "Replica leader not set for collection %s" (name dest-coll-name))))


(defn sync-all-collection
  "Sync config and aliases for a single collection"
  [dest live-nodes src colls coll]
  (try
    (let [[src-coll-name {:keys [aliases configName]}] coll
          dest-prefix (or (:dest-prefix src) "")
          dest-coll-name (keyword (str dest-prefix (name src-coll-name)))
          files (config-files src configName)
          v (version files)
          new-dest-cfg-name (str (name src-coll-name) "-" v)
          _sync-res (sync-config new-dest-cfg-name files dest)
          dest-colls (c/collections (c/cluster-status dest))
          _sync-coll-res (sync-collection dest-coll-name new-dest-cfg-name dest-colls dest live-nodes)
          coll-meta (collection-meta src dest-coll-name) ;; Beware of prefix!
          _ (sync-meta dest-coll-name dest-colls coll-meta dest)
          ;; sync aliases : TODO Should probably be covered in sync as we move to git sources 
          _sync_alias_res (sync-aliases src-coll-name
                                        (src-coll-name colls) ;; coll-meta should work and be used
                                        dest-coll-name
                                        (dest-coll-name dest-colls)
                                        dest
                                        dest-prefix)])
    (catch Exception e
      (srv/inc-coll-total (first coll) :update-errors)
      (errorf "Exception caught syncing collection %s from source %s : %s"
              coll
              (:base-url src)
              e))))

(defmulti source-colls file-source?)

(defmethod source-colls true
  [src]
  (->> (m/source-coll-dirs src)
       (map (fn [c]
              (let [meta-file (io/as-file (str (.getPath c) ".json"))
                    coll-meta (when (.isFile meta-file)
                                (-> meta-file slurp (json/parse-string keyword)))]
                [(-> c .getName keyword)
                 (assoc coll-meta :configName (-> c .getName))])))
       (into {})))

(defmethod source-colls false
  [src]
  (let [all-src-colls (c/collections (c/cluster-status src))
        flt-src-colls (:collections src)]
    (->> all-src-colls
         (filter (fn [[coll _]] (contains? flt-src-colls coll)))
         (into {}))))

(defn sync-source
  "Sync single source"
  [dest src]
  (infof "Reading state from source %s" (:base-url src))
  (try
    (let [src-colls (source-colls src)
          live-nodes (c/live-nodes (c/cluster-status dest))]
      (doall (map (partial sync-all-collection dest live-nodes src src-colls)
                  src-colls)))
    (catch Exception e
      (srv/inc-total :errors)
      (errorf "Exception caught syncing source %s : %s"
              (:base-url src)
              (.getMessage e)))))

(defn sync-sources
  "Sync all sources to destination"
  [dest sources]
  (infof "Syncing %d sources to %s" (count sources) (:base-url dest))
  (doall (map (partial sync-source dest)
              sources)))

(defn remove-unused-configs
  "Remove unused configs"
  [dest]
  (try
    ;; Beware of another instance that might create configs!
    (let [cfgs-1 (c/unused-configs dest)
          _ (Thread/sleep 5000)
          cfgs-2 (c/unused-configs dest)
          cfgs (filter #(contains? cfgs-2 %) cfgs-1)]
      (infof "Got %d unused configs to remove" (count cfgs))
      (doall (map (fn [cfg]
                    (info "Removing unused config" cfg "on" (:base-url dest))
                    (-> cfg
                        c/cmd-del-config
                        (c/execute dest)))
                  cfgs)))
    (catch Exception e
      (srv/inc-total :errors)
      (errorf "Exception caught removing unused configs from destination %s : %s"
              (:base-url dest)
              e))))

(defn remove-all
  "Removes, all collections, aliases and configs. Think twice! 💣"
  [dest]
  (let [server (:base-url dest)
        dest-colls (-> (c/cluster-status dest)
                       c/collections)
        cfgs (->> (c/configs dest)
                  (map :text)
                  (filter #(not= "_default" %)))]
    (infof "Removing all on %s" server)
    (doall (map (fn [[coll {:keys [aliases]}]]
                  (doall (map (fn [alias]
                                (infof "Removing alias %s on %s" alias server)
                                (-> (name alias)
                                    c/cmd-del-alias
                                    (c/execute dest)))
                              aliases))
                  (infof "Removing collection %s on %s" (name coll) server)
                  (c/execute (c/cmd-del-coll (name coll)) dest))
                dest-colls))
    (doall (map (fn [cfg]
                  (infof "Removing config %s on %s" cfg server)
                  (c/execute (c/cmd-del-config cfg) dest))
                cfgs))))

(defn write-config-zip [config-name src]
  (let [files (config-files src config-name)]
    (with-open [os (io/output-stream (str config-name ".zip"))]
      (zip-config files os))))

(defn synchronize [cfg destination]
  (let [dest-colls (srv/init! cfg)]
    (when (:pause cfg)
      (srv/server-start (:service-port cfg)))
    (reset! config cfg)
    ;; TODO: We should only use atom and we even want destination dynamic
    (loop []
      (sync-sources destination (:sources @config))
      (when (:remove-unused-config destination)
        (remove-unused-configs destination))
      (when-let [pause (:pause @config)]
        (infof "Pausing %d ms" pause)
        (Thread/sleep pause)
        (recur)))))


(def cli-options
  [["-d" "--destination <EDN spec>" "EDN specification overriding config"
    :parse-fn read-string]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> [""
        "Usage: satrn [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  remove             Remove all collections, configsets and aliases from destination"
        "  sync               Sync destination with sources"
        "  config-zip <name>  Get configset zip from destination"
        ""]
       (join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))


(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        cfg (-> (select-keys cfg/sync-defaults [:log-level :service-port])
                (merge (load-env)))
        _ (when-let [log-output-fn (:output-fn cfg)]
            (timbre/merge-config!
             {:min-level (-> cfg :log-level keyword)
              :output-fn (resolve log-output-fn)}))
        destination (cfg/deep-merge (:destination cfg/sync-defaults)
                                    (or (:destination options) (:destination cfg)))]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      ;; TODO: Migrate to subcommand!
      (and (= 1 (count arguments))
           (#{"remove" "sync"} (first arguments)))
      {:action (case (first arguments)
                 "sync" (partial synchronize cfg destination)
                 "remove" (partial remove-all destination))
       :options options}
      (and (= 2 (count arguments))
           (= "config-zip" (first arguments)))
      {:action (partial write-config-zip (second arguments) destination)
       :options options}
      ;; nil ;; TODO config
      ;; {:action (first arguments) :options options}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (cfg/exit (if ok? 0 1) exit-message)
      (action))))

;; TODO: No *file* in k8s job
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))


(comment
  ;; Playground
  (let [cfg (load-env)
        output-fn (:output-fn cfg)]
    (resolve output-fn))

  (let [cfg (load-env)
        dest (:destination cfg)
        sources (:sources cfg)]
    (sync-sources dest sources))

  (let [dest (read-string "{:base-url \"http://localhost:8983\" :basic-auth [\"solr\" \"abc\"]}")]
    ;; (remove-all dest)
    )
  ;;(require 'satrn-test)
  ;; (clojure.test/run-tests 'satrn-test)
  ;; *e
  #_(let [cfg (load-env)
          dest (:destination cfg)]
    ;; sources
      (remove-all dest)))
