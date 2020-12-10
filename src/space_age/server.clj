(ns space-age.server
  (:import (javax.net.ssl SSLSocket SSLServerSocket SSLServerSocketFactory))
  (:require [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.requests :refer [parse-uri]]
            [space-age.handler :refer [gemini-handler]]
            [space-age.mime-types :refer [load-mime-types!]]))

(defonce ^:private global-server-thread (atom nil))
(defonce ^:private global-server-socket (atom nil))

(defn- create-ssl-socket [port]
  (let [server-socket (-> (SSLServerSocketFactory/getDefault)
                          (.createServerSocket port))]
    (doto server-socket
      (.setSSLParameters (doto (.getSSLParameters server-socket)
                           (.setWantClientAuth true))))))

(defn- read-socket [^SSLSocket socket]
  (parse-uri (.readLine (io/reader socket))))

(defn- write-socket [^SSLSocket socket {:keys [status meta body]}]
  (doto (io/writer socket)
    (.write (str status " " meta "\r\n"))
    (.flush))
  (when body
    (with-open [in-stream (io/input-stream (if (string? body) (.getBytes body) body))]
      (let [out-stream (io/output-stream socket)]
        (.transferTo in-stream out-stream)
        (.flush out-stream))))
  (.shutdownOutput socket))

;; FIXME: Only accept connections passing an SNI HostName that matches (System/getProperty "sni.hostname")
;; FIXME: Extract client cert if one is provided: (.getSession ^SSLSocket socket)
(defn- accept-connections! [^SSLServerSocket server-socket document-root]
  (while @global-server-thread
    (try
      (let [socket (.accept server-socket)]
        (try
          (->> (read-socket socket)
               (gemini-handler document-root)
               (write-socket socket))
          (catch Exception e
            (log "Server error:" e))
          (finally (.close socket))))
      (catch Exception _))))

(defn- stop-server! []
  (if @global-server-thread
    (do
      (reset! global-server-thread nil)
      (when @global-server-socket
        (.close @global-server-socket)
        (reset! global-server-socket nil))
      (log "Server stopped."))
    (log "Server is not running.")))

(defn- start-server! [document-root port]
  (if @global-server-thread
    (log "Server is already running.")
    (do
      (log "Loading database of MIME types.")
      (load-mime-types!)
      (reset! global-server-thread
              (future
                (try
                  (with-open [server-socket (create-ssl-socket port)]
                    (reset! global-server-socket server-socket)
                    (log (str "Gemini server started on port " port "."))
                    (accept-connections! server-socket document-root))
                  (catch Exception e
                    (log "Error creating SSL server socket:" e)
                    (stop-server!))))))))

(defn- get-int-port [port]
  (cond
    (nil? port)     1965
    (integer? port) port
    (string? port)  (Integer/parseInt port)))

(defn- valid-port? [port]
  (or (nil? port)
      (integer? port)
      (and (string? port)
           (try (Integer/parseInt port)
                (catch Exception _ false)))))

(defn- check-inputs [document-root port]
  (cond
    (not (string? document-root))
    "Missing inputs: document-root [port]"

    (not (.isDirectory (io/file document-root)))
    (str "Document root " document-root " is not a directory.")

    (not (.canRead (io/file document-root)))
    (str "No read access to document root " document-root ".")

    (not (valid-port? port))
    "The port argument must be a valid integer if specified."

    (nil? (System/getProperty "sni.hostname"))
    "Missing sni.hostname property. Please set this in deps.edn."))

(def ^:private program-banner "
:'#####::'#######:::::'##:::::'#####::'#######:::::::'##:::::'#####:::'#######:
'##.. ##: ##... ##:::'####:::'##.. ##: ##....:::::::'####:::'##.. ##:: ##....::
 ##::..:: ##::: ##::'##: ##:: ##::..:: ##::::::::::'##: ##:: ##::..::: ##::::::
. #####:: #######::'##::. ##: ##:::::: #####::::::'##::. ##: ##:'####: #####:::
:.... ##: ##....::: ########: ##:::::: ##..::::::: ########: ##:: ##:: ##..::::
'##:: ##: ##::::::: ##... ##: ##:: ##: ##::::::::: ##... ##: ##:: ##:: ##::::::
. #####:: ##::::::: ##::: ##:. #####:: #######:::: ##::: ##:. #####::: #######:
:.....:::..::::::::..::::..:::.....:::.......:::::..::::..:::.....::::.......::
                        (The Clojurian's Gemini Server)
")

(defn -main [& [document-root port]]
  (println program-banner)
  (if-let [error-msg (check-inputs document-root port)]
    (log error-msg)
    (do
      (start-server! document-root (get-int-port port))
      (deref @global-server-thread)))
  (shutdown-agents))
