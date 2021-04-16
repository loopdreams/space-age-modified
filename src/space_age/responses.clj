(ns space-age.responses
  (:import java.io.File))

(defn input-response [prompt]
  {:status 10
   :meta   prompt})

(defn sensitive-input-response [prompt]
  {:status 11
   :meta   prompt})

;; body can be either a string or a java.io.File
(defn success-response [type body]
  {:status 20
   :meta   type
   :body   body})

(defn temporary-redirect-response [uri]
  {:status 30
   :meta   uri})

(defn permanent-redirect-response [uri]
  {:status 31
   :meta   uri})

(defn temporary-failure-response [msg]
  {:status 40
   :meta   msg})

(defn server-unavailable-response [msg]
  {:status 41
   :meta   msg})

(defn cgi-error-response [msg]
  {:status 42
   :meta   msg})

(defn proxy-error-response [msg]
  {:status 43
   :meta   msg})

(defn slow-down-response [wait-seconds]
  {:status 44
   :meta   wait-seconds})

(defn permanent-failure-response [msg]
  {:status 50
   :meta   msg})

(defn not-found-response [msg]
  {:status 51
   :meta   msg})

(defn gone-response [msg]
  {:status 52
   :meta   msg})

(defn proxy-request-refused-response [msg]
  {:status 53
   :meta   msg})

(defn bad-request-response [msg]
  {:status 59
   :meta   msg})

(defn client-certificate-required-response [msg]
  {:status 60
   :meta   msg})

(defn client-certificate-not-authorised-response [msg]
  {:status 61
   :meta   msg})

(defn client-certificate-not-valid-response [msg]
  {:status 62
   :meta   msg})

(def valid-status-codes #{10 11 20 30 31 40 41 42 43 44 50 51 52 53 59 60 61 62})

(defn valid-response? [response]
  (and (map? response)
       (contains? valid-status-codes (:status response))
       (or (string? (:meta response))
           (integer? (:meta response)))
       (or (nil? (:body response))
           (string? (:body response))
           (instance? File (:body response)))))
