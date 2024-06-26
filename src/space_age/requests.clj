(ns space-age.requests
  (:import java.net.URI)
  (:require [clojure.string :as str]))

;; Example URI: gemini://myhost.org/foo/bar.clj?baz\r\n
(defn parse-uri [uri]
  (try
    (let [uri-obj (URI/create (str/trim uri))]
      {:uri          uri
       :scheme       (str/lower-case (.getScheme uri-obj))
       :host         (str/lower-case (.getHost uri-obj))
       :port         (if (pos? (.getPort uri-obj)) (.getPort uri-obj) 1965)
       :raw-path     (if (str/blank? (.getRawPath uri-obj)) "/" (.getRawPath uri-obj))
       :path         (if (str/blank? (.getPath uri-obj)) "/" (.getPath uri-obj))
       :raw-query    (.getRawQuery uri-obj)
       :query        (.getQuery uri-obj)})
    (catch Exception _ {:uri uri :parse-error? true})))

(defn valid-request? [request]
  (every? #(contains? request %)
          [:uri :scheme :host :port :raw-path :path :raw-query :query]))
