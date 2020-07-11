(ns space-age.handler
  (:import java.net.URI)
  (:require [clojure.string :as str]
            [space-age.logging :refer [log]]
            [space-age.responses :refer [permanent-failure-response
                                         process-request]]))

;; FIXME: Do we expect kv pairs using Gemini?
(defn parse-query [query]
  (if (str/blank? query)
    {}
    (apply hash-map (str/split query #"&|="))))

(defn parse-uri [uri]
  (try
    (let [uri-obj (URI/create (str/trim uri))]
      {:scheme (str/lower-case (.getScheme uri-obj))
       :host   (str/lower-case (.getHost uri-obj))
       :port   (if (pos? (.getPort uri-obj)) (.getPort uri-obj) 1965)
       :route  (.getPath uri-obj)
       :params (parse-query (.getQuery uri-obj))})
    (catch Exception e nil)))

;; Example URI: gemini://myhost.org/foo/bar?baz=buzz&boz=bazizzle\r\n
(defn gemini-handler [document-root uri]
  (log uri)
  (if-let [request (parse-uri uri)]
    (if (not= "gemini" (:scheme request))
      (permanent-failure-response (str "Protocol \"" (:scheme request) "\" is not supported."))
      (process-request document-root request))
    (permanent-failure-response "Malformed URI.")))
