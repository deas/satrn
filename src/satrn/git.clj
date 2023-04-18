(ns satrn.git
  (:import (java.util.zip ZipInputStream)
           (java.io FileInputStream File))
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :refer [info infof error errorf warn warnf]]
            [clojure.string :as str]))

;; GIT_SSH_COMMAND
(defn config-path
  [name]
  (str (System/getProperty "java.io.tmpdir")
       File/separatorChar
       name
       ".zip"))

;; TODO: Should probably go to misc ns along with zip-config
(defn unzip-config
  [config-name to-dir]
  (let [;; to-dir (config-path config-name)
        zip-file (-> config-name config-path io/file)]
    (with-open [zip-is (ZipInputStream. (FileInputStream. zip-file))]
      (loop [entry (.getNextEntry zip-is)]
        (when entry
          (let [save-path (str to-dir File/separatorChar (.getName entry))
                save-file (File. save-path)]
            (println save-path (.isDirectory entry))
            (if (.isDirectory entry)
              (when-not (.exists save-file)
                (.mkdirs save-file))
              (let [parent-dir (File. (.substring save-path 0 (.lastIndexOf save-path (int File/separatorChar))))]
                (when-not (.exists parent-dir)
                  (.mkdirs parent-dir))
                (clojure.java.io/copy zip-is save-file)))
            (recur (.getNextEntry zip-is))))))))

(defn save-config [source config-name]
  (io/copy source (-> config-name config-path io/file)))

(defn update-collection-source [collection config-name]
  )


(comment
  (let [cfg "satrn-test-config"
        zip-name "sample_techproducts_configs.zip"]
    (save-config (io/input-stream (io/resource zip-name)) cfg)
    (unzip-config cfg (str (System/getProperty "java.io.tmpdir")
                           File/separatorChar
                           cfg)))
  )
