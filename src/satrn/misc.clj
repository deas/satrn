(ns satrn.misc
  (:require [clojure.java.io :as io])
  (:import [java.security SecureRandom MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.util Base64]
           [java.net URLEncoder URLDecoder]))

;; Borrowed from httpkit bc not in version shipping with bb atm
(defn url-encode [s]
  (URLEncoder/encode (str s) "utf8"))

(defn url-decode [s]
  (URLDecoder/decode s "utf8"))

(defn encode-base64 [b]
  (.encodeToString (Base64/getEncoder) b))

(defn source-coll-dirs
  [src]
  (let [root-path (-> src :base-url io/as-url .getFile)
        root-file (io/as-file root-path)]
    (when-not (.isDirectory root-file)
      (throw (Exception. (str root-path " is not a directory"))))
    (->> (-> root-file .list seq #_io/as-file #_file-seq)
         (map #(-> (str root-path java.io.File/separator %) io/as-file))
         (filter #(.isDirectory %)))))

(defn solr-password-hash [password]
  (let [salt (byte-array 32)
        _ (-> (SecureRandom.)
              (.nextBytes salt))
        digest (doto (MessageDigest/getInstance "SHA-256")
                 .reset
                 (.update salt))
        bt-pass-1 (.digest digest (.getBytes password StandardCharsets/UTF_8))
        _ (.reset digest)
        bt-pass-2 (.digest digest bt-pass-1)]
    (str (encode-base64 bt-pass-2) " " (encode-base64 salt))))

(comment
  (url-decode "com.jayway.jsonpath.internal%3AINFO")
  (solr-password-hash "password")
  )
