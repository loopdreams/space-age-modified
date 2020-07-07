(ns space-age.mime-types
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defonce mime-types (atom {}))

(defn load-mime-types! []
  (reset! mime-types
          (with-open [reader (io/reader (io/resource "mime.types"))]
            (into {}
                  (mapcat (fn [line]
                            (let [[mime-type & extensions] (str/split line #"\s+")
                                  mime-type-complete (if (str/starts-with? mime-type "text/")
                                                       (str mime-type "; charset=utf-8")
                                                       mime-type)]
                              (map (fn [ext] [ext mime-type-complete]) extensions))))
                  (line-seq reader))))
  nil)

(defn get-extension [filename]
  (when-let [last-dot (str/last-index-of filename \.)]
    (str/lower-case (subs filename (inc last-dot)))))

;; FIXME: Detect if a filename with no extension is text or binary
(defn get-mime-type [filename]
  (get @mime-types (get-extension filename) "application/octet-stream"))
