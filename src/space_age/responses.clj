(ns space-age.responses)

(defn input-response [prompt]
  {:status 10
   :meta   prompt})

;; body can be either a string or a java.io.File
(defn success-response [type body]
  {:status 20
   :meta   type
   :body   body})

(defn redirect-response [uri]
  {:status 30
   :meta   uri})

(defn temporary-failure-response [msg]
  {:status 40
   :meta   msg})

(defn permanent-failure-response [msg]
  {:status 50
   :meta   msg})

;; FIXME: Implement client certificates
(defn client-certificate-required-response [msg]
  {:status 60
   :meta   msg})
