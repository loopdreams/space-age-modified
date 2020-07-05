(ns space-age.handler
  (:require [clojure.string :as str]
            [space-age.logging :refer [log]]))

(def gemini-uri-regex #"^([a-z]+)://([^/]+)([^\?]*)(\?.+)?$")

;; FIXME: %-decode k and v
(defn parse-query [query]
  (if query
    (let [kv-pairs (-> query
                       (subs 1)
                       (str/split #"&"))]
      (reduce (fn [acc kv-pair]
                (let [[k v] (str/split kv-pair #"=")]
                  (assoc acc k v)))
              {}
              kv-pairs))
    {}))

(defn parse-uri [uri]
  (when-let [[uri scheme host path query] (->> uri
                                               (str/trim)
                                               (str/lower-case)
                                               (re-find gemini-uri-regex))]
    {:uri    uri
     :scheme scheme
     :host   host
     :path   path
     :params (parse-query query)}))

(defn query-response [prompt]
  (str "10 " prompt "\r\n"))

;; FIXME: Handle binary data in body
(defn success-response [type body]
  (str "20 " type "\r\n" body))

(defn redirect-response [uri]
  (str "30 " uri "\r\n"))

(defn temporary-failure-response [msg]
  (str "40 " msg "\r\n"))

(defn permanent-failure-response [msg]
  (str "50 " msg "\r\n"))

;; FIXME: stub
(defn client-certificate-required-response []
  (str "60\r\n"))

;; FIXME: Verify that these are correct
(def mime-type {"gmi"    "text/gemini; charset=utf-8"
                "gemini" "text/gemini; charset=utf-8"
                "txt"    "text/plain; charset=utf-8"
                "html"   "text/html; charset=utf-8"
                "png"    "image/png"
                "gif"    "image/gif"
                "jpg"    "image/jpeg"
                "jpeg"   "image/jpeg"})

;; FIXME: stub
;; Example URI: gemini://myhost.org/foo/bar?baz=buzz&boz=bazizzle
(defn gemini-handler [uri]
  (log uri)
  (if-let [{:keys [uri scheme host path params] :as request} (parse-uri uri)]
    (success-response (mime-type "txt") (str request))
    (permanent-failure-response "Malformed URI")))
