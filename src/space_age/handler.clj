(ns space-age.handler
  (:import java.net.URLDecoder
           java.nio.charset.StandardCharsets)
  (:require [clojure.string :as str]
            [space-age.logging :refer [log]]
            [space-age.responses :refer [permanent-failure-response
                                         process-request]]))

(def utf-8 (.name StandardCharsets/UTF_8))

(defn url-decode [s]
  (URLDecoder/decode s utf-8))

(defn parse-query [query]
  (if (str/blank? query)
    {}
    (let [kv-pairs (-> query
                       (subs 1)
                       (str/split #"&"))]
      (reduce (fn [acc kv-pair]
                (let [[k v] (str/split kv-pair #"=")]
                  (assoc acc (url-decode k) (url-decode v))))
              {}
              kv-pairs))))

(def gemini-uri-regex #"^([A-Za-z]+)://([^/]+)/?([^\?]*)(\?.+)?$")

(defn parse-uri [uri]
  (try
    (when-let [[_ scheme host+port route query] (->> uri
                                                     (str/trim)
                                                     (re-find gemini-uri-regex))]
      (let [[host port] (str/split host+port #":")]
        {:scheme  (str/lower-case scheme)
         :host    (str/lower-case host)
         :port    (if port (Integer/parseInt port) 1965)
         :route   route
         :params  (parse-query query)}))
    (catch Exception e nil)))

;; Example URI: gemini://myhost.org/foo/bar?baz=buzz&boz=bazizzle\r\n
(defn gemini-handler [document-root uri]
  (log uri)
  (if-let [request (parse-uri uri)]
    (if (not= "gemini" (:scheme request))
      (permanent-failure-response (str "Protocol \"" (:scheme request) "\" is not supported."))
      (process-request document-root request))
    (permanent-failure-response "Malformed URI.")))
