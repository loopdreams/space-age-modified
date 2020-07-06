(ns space-age.server
  (:import java.net.ServerSocket)
  (:require [clojure.java.io :as io]
            [space-age.handler :refer [gemini-handler]]
            [space-age.logging :refer [log]]))

(defonce server-running? (atom false))

(defn read-socket [socket]
  (.readLine (io/reader socket)))

(defn write-socket [socket msg]
  (let [writer (io/writer socket)]
    (.write writer msg)
    (.flush writer)))

;; FIXME: Implement TLS handshake (see section 4 of Gemini spec)
(defn start-server! [port handler]
  (if @server-running?
    (log "Server is already running.")
    (do
      (reset! server-running? true)
      (future
        (with-open [server-socket (ServerSocket. port)]
          (while @server-running?
            (with-open [socket (.accept server-socket)]
              (write-socket socket (handler (read-socket socket))))))))))

(defn stop-server! []
  (if @server-running?
    (do
      (reset! server-running? false)
      (log "Server stopped."))
    (log "Server is not running.")))

(defn -main [& [port]]
  (let [port (cond
               (nil? port)     1965
               (integer? port) port
               (string? port)  (Integer/parseInt port)
               :else           1965)]
    (start-server! port gemini-handler)))

;; 4 TLS

;; Use of TLS for Gemini transactions is mandatory.

;; Use of the Server Name Indication (SNI) extension to TLS is also mandatory, to facilitate name-based
;; virtual hosting.

;; 4.1 Version requirements

;; Servers MUST use TLS version 1.2 or higher and SHOULD use TLS version 1.3 or higher.  TLS 1.2 is
;; reluctantly permitted for now to avoid drastically reducing the range of available implementation
;; libraries.  Hopefully TLS 1.3 or higher can be specced in the near future.  Clients who wish to be
;; "ahead of the curve MAY refuse to connect to servers using TLS version 1.2 or lower.

;; 4.2 Server certificate validation

;; Clients can validate TLS connections however they like (including not at all) but the strongly
;; RECOMMENDED approach is to implement a lightweight "TOFU" certificate-pinning system which treats
;; self-signed certificates as first- class citizens.  This greatly reduces TLS overhead on the network
;; (only one cert needs to be sent, not a whole chain) and lowers the barrier to entry for setting up a
;; Gemini site (no need to pay a CA or setup a Let's Encrypt cron job, just make a cert and go).

;; TOFU stands for "Trust On First Use" and is public-key security model similar to that used by
;; OpenSSH.  The first time a Gemini client connects to a server, it accepts whatever certificate it is
;; presented.  That certificate's fingerprint and expiry date are saved in a persistent database (like
;; the .known_hosts file for SSH), associated with the server's hostname.  On all subsequent
;; connections to that hostname, the received certificate's fingerprint is computed and compared to the
;; one in the database.  If the certificate is not the one previously received, but the previous
;; certificate's expiry date has not passed, the user is shown a warning, analogous to the one web
;; browser users are shown when receiving a certificate without a signature chain leading to a trusted
;; CA.

;; This model is by no means perfect, but it is not awful and is vastly superior to just accepting
;; self-signed certificates unconditionally.

;; 4.3 Client certificates

;; Although rarely seen on the web, TLS permits clients to identify themselves to servers using
;; certificates, in exactly the same way that servers traditionally identify themselves to the client.
;; Gemini includes the ability for servers to request in-band that a client repeats a request with a
;; client certificate.  This is a very flexible, highly secure but also very simple notion of client
;; identity with several applications:

;; • Short-lived client certificates which are generated on demand and deleted immediately after use
;;   can be used as "session identifiers" to maintain server-side state for applications.  In this
;;   role, client certificates act as a substitute for HTTP cookies, but unlike cookies they are
;;   generated voluntarily by the client, and once the client deletes a certificate and its matching
;;   key, the server cannot possibly "resurrect" the same value later (unlike so-called "super
;;   cookies").
;; • Long-lived client certificates can reliably identify a user to a multi-user application without
;;   the need for passwords which may be brute-forced.  Even a stolen database table mapping
;;   certificate hashes to user identities is not a security risk, as rainbow tables for certificates
;;   are not feasible.
;; • Self-hosted, single-user applications can be easily and reliably secured in a manner familiar from
;;   OpenSSH: the user generates a self-signed certificate and adds its hash to a server-side list of
;;   permitted certificates, analogous to the .authorized_keys file for SSH).

;; Gemini requests will typically be made without a client certificate.  If a requested resource
;; requires a client certificate and one is not included in a request, the server can respond with a
;; status code of 60, 61 or 62 (see Appendix 1 below for a description of all status codes related to
;; client certificates).  A client certificate which is generated or loaded in response to such a
;; status code has its scope bound to the same hostname as the request URL and to all paths below the
;; path of the request URL path.  E.g. if a request for gemini://example.com/foo returns status 60 and
;; the user chooses to generate a new client certificate in response to this, that same certificate
;; should be used for subseqent requests to gemini://example.com/foo, gemini://example.com/foo/bar/,
;; gemini://example.com/foo/bar/baz, etc., until such time as the user decides to delete the
;; certificate or to temporarily deactivate it.  Interactive clients for human users are strongly
;; recommended to make such actions easy and to generally give users full control over the use of
;; client certificates.
