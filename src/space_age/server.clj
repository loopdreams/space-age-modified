(ns space-age.server
  (:import (javax.net.ssl SSLServerSocket SSLServerSocketFactory))
  (:require [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.handler :refer [parse-uri gemini-handler]]
            [space-age.mime-types :refer [load-mime-types!]]))

(defonce server-running? (atom false))

;; FIXME: Activate SNI extension
(defn create-ssl-socket [port]
  (.createServerSocket (SSLServerSocketFactory/getDefault) port))

(defn read-socket [socket]
  (parse-uri (.readLine (io/reader socket))))

(defn write-socket [socket {:keys [status meta body]}]
  (doto (io/writer socket)
    (.write (str status " " meta "\r\n"))
    (.flush))
  (with-open [in-stream  (io/input-stream (if (string? body) (.getBytes body) body))
              out-stream (io/output-stream socket)]
    (.transferTo in-stream out-stream)
    (.flush out-stream)))

(defn start-server! [& [document-root port]]
  (let [port (cond
               (nil? port)     1965
               (integer? port) port
               (string? port)  (Integer/parseInt port)
               :else           1965)]
    (cond
      @server-running?
      (log "Server is already running.")

      (not (string? document-root))
      (log "Missing inputs: document-root [port]")

      (not (.isDirectory (io/file document-root)))
      (log "Document root" document-root "is not a directory.")

      (not (.canRead (io/file document-root)))
      (log (str "No read access to document root " document-root "."))

      :else
      (do
        (log (str "Starting server on port " port "."))
        (load-mime-types!)
        (reset! server-running? true)
        (future
          (with-open [^SSLServerSocket server-socket (create-ssl-socket port)]
            (while @server-running?
              (try
                (with-open [socket (.accept server-socket)]
                  (->> (read-socket socket)
                       (gemini-handler document-root)
                       (write-socket socket)))
                (catch Exception e (log "Server error:" e))))))))))

(defn stop-server! []
  (if @server-running?
    (do
      (reset! server-running? false)
      (log "Server stopped."))
    (log "Server is not running.")))

(def -main start-server!)
