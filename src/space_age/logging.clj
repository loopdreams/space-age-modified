(ns space-age.logging
  (:import java.text.SimpleDateFormat
           java.util.Date)
  (:require [clojure.string :as str]))

(defonce synchronized-log-writer (agent nil))

(defn truncate [s max-length]
  (if (> (count s) max-length)
    (subs s 0 max-length)
    s))

(defn log [& vals]
  (when (seq vals)
    (let [message   (truncate (str/join " " vals) 500)
          timestamp (.format (SimpleDateFormat. "YYYY-MM-dd HH:mm:ss") (Date.))]
      (send-off synchronized-log-writer
                (fn [_] (println timestamp message)))
      nil)))
