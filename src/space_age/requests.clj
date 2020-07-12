(ns space-age.requests
  (:import java.net.URI)
  (:require [clojure.string :as str]))

;; FIXME: Do we expect kv pairs using Gemini?
(defn parse-query [query]
  (if (str/blank? query)
    {}
    (apply hash-map (str/split query #"&|="))))

(defn parse-uri [uri]
  (try
    (let [uri-obj (URI/create (str/trim uri))]
      {:uri    uri
       :scheme (str/lower-case (.getScheme uri-obj))
       :host   (str/lower-case (.getHost uri-obj))
       :port   (if (pos? (.getPort uri-obj)) (.getPort uri-obj) 1965)
       :path   (.getPath uri-obj)
       :params (parse-query (.getQuery uri-obj))})
    (catch Exception e {:uri uri :parse-error? true})))

