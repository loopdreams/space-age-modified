(ns space-age.server
  (:import java.net.ServerSocket)
  (:require [clojure.java.io :as io]
            [space-age.handler :refer [gemini-handler]]
            [space-age.logging :refer [log]]))

(defn read-socket
  "Read a line of textual data from the given socket"
  [socket]
  (let [reader (io/reader socket)]
    (.readLine reader)))

(defn write-socket
  "Send the given string message out over the given socket"
  [socket msg]
  (let [writer (io/writer socket)]
    (.write writer msg)
    (.flush writer)))

(defonce server-running? (atom false))

;; FIXME: Implement TLS handshake (see section 4 of Gemini spec)
(defn start-server! [port handler]
  (future
    (with-open [server-socket (ServerSocket. port)]
      (reset! server-running? true)
      (while @server-running?
        (with-open [socket (.accept server-socket)]
          (write-socket socket (handler (read-socket socket))))))))

(defn stop-server! []
  (reset! server-running? false)
  (log "Server stopped."))

(defn -main [& [port]]
  (let [port (cond
               (nil? port)     1965
               (integer? port) port
               (string? port)  (Integer/parseInt port)
               :else           1965)]
    (start-server! port gemini-handler)))
