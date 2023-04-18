#!/usr/bin/env bb
(ns rnb
  (:gen-class
   :name satrn.RnB
   :main true
   :prefix "-")
  (:require
   [satrn.config :as cfg]
   [satrn.command :as c]
   [clj-yaml.core :as yaml]
   ;; [clojure.tools.logging :refer [info infof error errorf warn warnf]]
   ;; [taoensso.timbre :as timbre]
   [clojure.string :refer [join]]
   ;; [clojure.set :refer [difference]]
   [clojure.tools.cli :refer [parse-opts]]))
               
(def cli-options
  [["-d" "--destination <EDN spec>" "EDN specification overriding config"
    :default (:destination cfg/common-defaults)
    :parse-fn read-string]
   ["-h" "--help"]])

(def rnb-cli-options
  [["-r" "--repository <repository>" "Repository name"]
   ["-l" "--location <location>" "Location name"]
   ["-n" "--name <name>" "Name of the resource"]
   ["-h" "--help"]])

(def k8s-cli-options (concat rnb-cli-options
                             [["-c" "--cloud <cloud>" "Cloud name" :required true]]))

(defn usage [options-summary]
  (->> [""
        "Usage: rnb [options] action [action-options]"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  backup           Backup collections, configsets and aliases"
        "  restore          Restore collections"
        "  delete           Delete backup"
        "  request-status   Request backup status "
        "  delete-status    Delete backup status "
        "  k8s              Generate Kubernetes SolrBackup resource"
        ""]
       (join \newline)))

(defn k8s-usage [options-summary]
  (->> [""
        "Usage: k8s [action-options]"
        ""
        "Options:"
        options-summary
        ""]
       (join \newline)))

(defn rnb-usage [command options-summary]
  (->> [""
        (str "Usage: " command " [action-options] collection_1 ...")
        ""
        "Options:"
        options-summary
        ""]
       (join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))


(defn- opt-collections [base-options arguments]
  (or (seq arguments)
      (->> (c/cluster-status (:destination base-options))
           c/collections
           (map (fn [[k _]] (name k))))))

(defn validate-k8s-args [base-options args]
  (let [{:keys [options arguments parse-errors summary]} (parse-opts args k8s-cli-options)
        {:keys [location name cloud]} options
        errors (cond-> parse-errors
                 (some nil? [location name cloud])
                 (conj "You must specify a location, name and cloud"))]
    (cond
      (:help options)
      {:exit-message (k8s-usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      :else
      {:action #(-> (cfg/k8s-backup-resources
                     options
                     (opt-collections base-options arguments))
                    (yaml/generate-string :dumper-options {:flow-style :block}))}
      ;; :else ; failed custom validation => exit with usage summary
      ;; {:exit-message (usage summary)}
      )))

(defn validate-rnb-args [base-options command args]
  (let [{:keys [options arguments parse-errors summary]} (parse-opts args rnb-cli-options)
        {:keys [location name]} options
        errors (cond-> parse-errors
                 (some nil? [location name])
                 (conj "You must specify a location and name"))
        req-id-fn (fn [[c l n]] [(format "%s/%s-%s" l n c)])
        rnb-cmd (case command
                  "backup" c/cmd-backup-coll
                  "restore" c/cmd-restore-coll
                  "delete" c/cmd-del-backup
                  "request-status" (comp c/cmd-request-status req-id-fn)
                  "delete-status" (comp c/cmd-del-status req-id-fn))]
    (cond
      (:help options)
      {:exit-message (rnb-usage command summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      :else
      {:action (fn [] (doall (map #(-> [% (:location options) (:name options)] rnb-cmd c/execute c/response-map)
                                  (opt-collections base-options arguments))))})))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options :in-order true)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (< 0 (count arguments))
      (case (first arguments)
        "k8s" (validate-k8s-args options (rest arguments))
        (validate-rnb-args options (first arguments) (rest arguments)))
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))


(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (cfg/exit (if ok? 0 1) exit-message)
      (println (action)))))

;; TODO: No *file* in k8s job
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  ;; Playground
  )
