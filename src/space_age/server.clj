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
