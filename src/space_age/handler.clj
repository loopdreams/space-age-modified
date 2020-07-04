(ns space-age.handler)

(defn gemini-handler [request]
  (println request)
  "Hello, Gemini!")

;; C:   Sends request (one CRLF terminated line) (see section 2)
;; S:   Sends response header (one CRLF terminated line), closes connection
;;      under non-success conditions (see 3.1 and 3.2)
;; S:   Sends response body (text or binary data) (see 3.3)

;; REQUEST: URL<CR><LF>
;; RESPONSE: <STATUS><SPACE><META><CR><LF>

;; STATUS:
;; 10 QUERY
;; 20 SUCCESS
;; 30 REDIRECT
;; 40 TEMPORARY FAILURE
;; 50 PERMANENT FAILURE
;; 60 CLIENT CERTIFICATE REQUIRED

;; SUCCESS RESPONSE EXAMPLE:
;; 20 text/gemini; charset=utf-8\r\n
;; # Hello world!
;;
;; This is my cool Gemini page.
