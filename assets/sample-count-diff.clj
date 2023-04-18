#!/usr/bin/env bb
;; Count differences between two solr instances
(require ;; '[clojure.java.shell :as shell]
         '[cnt-qdff :refer [cnt-qdiff]]
         '[satrn.misc :as m]
         '[clojure.pprint :refer [pprint]])

(def auth ["admin" "admin"
           #_(:out (shell/sh "gcloud" "--project=abcd" "secrets" "versions" "access" "latest" "--secret=solr-admin-prod-password"))])
(def endpoints ["http://endpoint-1:8983" "http://endpoint-2:8983"])

(pprint (map (partial cnt-qdiff (first endpoints) (second endpoints) auth)
             (->> *command-line-args* (map m/url-encode))))