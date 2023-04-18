#!/usr/bin/env bb
(require '[clojure.test :as t]
         '[babashka.classpath :as cp])

(cp/add-classpath "src:test")                        

(require 'satrn-test #_'your.test-b)                  

(def test-results
  (t/run-tests 'satrn-test #_'your.test-b))           

(def failures-and-errors
  (let [{:keys [:fail :error]} test-results]
    (+ fail error)))                                 

(System/exit failures-and-errors)
