#!/usr/bin/env bb
(import '[java.io PrintStream ByteArrayOutputStream ByteArrayInputStream])
(require ';; [clojure.java.shell :as shell]
         '[satrn.docs :refer [export-docs!]]
         '[dff :refer [json-diff]]
         '[clojure.pprint :refer [pprint]])

;; Parameters
(def auth ["admin" "admin"])
(def endpoints ["http://endpoint-1:8983" "http://endpoint-2:8983"])

(def base-export-opts {:collection "sample"
                       :num 50
                       :batch-size 100
                       :handler "search"
                       :wrap true})
(def prefix-ids true)


(defn diff
  [endpoint-1 endpoint-2 auth base-opts q]
  (let [baos-1 (ByteArrayOutputStream.)
        baos-2 (ByteArrayOutputStream.)
        _ (export-docs! (PrintStream. baos-1) (merge base-opts
                                                     {:query q
                                                      :source {:base-url endpoint-1
                                                               :basic-auth auth}}))
        _ (export-docs! (PrintStream. baos-2) (merge base-opts
                                                     {:query q
                                                      :source {:base-url endpoint-2
                                                               :basic-auth auth}}))]

    [q (pop (json-diff (ByteArrayInputStream. (.toByteArray baos-1))
                       (ByteArrayInputStream. (.toByteArray baos-2))
                       prefix-ids))]))

(pprint
 (map (partial diff (first endpoints) (second endpoints) auth base-export-opts)
      *command-line-args*))