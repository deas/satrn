(ns satrn.interop
  (:gen-class
   ;; :implements   [org...]
   :name satrn.InterOpDemo
   ;; :state state
   ;; :init init
   :main false
   :methods [^{:static true} [createSolrPasswordHash [String] String]]
   :prefix "-")
  (:require
   [satrn.misc :as misc]))


(defn -createSolrPasswordHash [str]
  (misc/solr-password-hash str))

;; Very basic demo for the moment
(defn -main [& args]
  (println "Solr password hash of"
           (first args)
           "is"
           (-createSolrPasswordHash (first args))))
