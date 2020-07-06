(ns space-age.handler
  (:import java.net.URLDecoder
           java.nio.charset.StandardCharsets)
  (:require [clojure.string :as str]
            [space-age.logging :refer [log]]
            [space-age.responses :refer [mime-type
                                         success-response
                                         permanent-failure-response]]))

(def utf-8 (.name StandardCharsets/UTF_8))

(defn url-decode [s]
  (URLDecoder/decode s utf-8))

(defn parse-query [query]
  (if query
    (let [kv-pairs (-> query
                       (subs 1)
                       (str/split #"&"))]
      (reduce (fn [acc kv-pair]
                (let [[k v] (str/split kv-pair #"=")]
                  (assoc acc (url-decode k) (url-decode v))))
              {}
              kv-pairs))
    {}))

(def gemini-uri-regex #"^([a-z]+)://([^/]+)([^\?]*)(\?.+)?$")

(defn parse-uri [uri]
  (try
    (when-let [[_ scheme host+port path query] (->> uri
                                                    (str/trim)
                                                    (str/lower-case)
                                                    (re-find gemini-uri-regex))]
      (let [[host port] (str/split host+port #":")]
        {:scheme scheme
         :host   host
         :port   (if port (Integer/parseInt port) 1965)
         :path   path
         :params (parse-query query)}))
    (catch Exception e nil)))

;; FIXME: Handle conditions on successful URI parsing
;; Example URI: gemini://myhost.org/foo/bar?baz=buzz&boz=bazizzle\r\n
(defn gemini-handler [uri]
  (log uri)
  (if-let [{:keys [scheme host port path params] :as request} (parse-uri uri)]
    (success-response (mime-type "txt") (str request))
    (permanent-failure-response "Malformed URI")))
