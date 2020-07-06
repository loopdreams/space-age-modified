(ns space-age.responses)

(defn input-response [prompt]
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
(defn client-certificate-required-response [msg]
  (str "60 " msg "\r\n"))

;; FIXME: Verify that these are correct
(def mime-type {"gmi"    "text/gemini; charset=utf-8"
                "gemini" "text/gemini; charset=utf-8"
                "txt"    "text/plain; charset=utf-8"
                "html"   "text/html; charset=utf-8"
                "png"    "image/png"
                "gif"    "image/gif"
                "jpg"    "image/jpeg"
                "jpeg"   "image/jpeg"})
