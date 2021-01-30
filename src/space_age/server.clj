(ns space-age.server
  (:import (java.io BufferedReader FileInputStream)
           (javax.net.ssl SSLSession SSLContext SSLServerSocket SSLSocket SSLParameters
                          KeyManagerFactory TrustManager X509TrustManager)
           (java.security KeyStore SecureRandom MessageDigest)
           (java.security.cert X509Certificate))
  (:require [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.requests :refer [parse-uri]]
            [space-age.handler :refer [gemini-handler]]
            [space-age.mime-types :refer [load-mime-types!]]))

(defonce ^:private global-server-thread (atom nil))
(defonce ^:private global-server-socket (atom nil))

;; FIXME: Unused. Remove after implementing SNI.
(defn- expand-ssl-params [^SSLParameters params]
  {:algorithm-constraints             (.getAlgorithmConstraints params)
   :application-protocols             (.getApplicationProtocols params)
   :cipher-suites                     (.getCipherSuites params)
   :enable-retransmissions?           (.getEnableRetransmissions params)
   :endpoint-identification-algorithm (.getEndpointIdentificationAlgorithm params)
   :maximum-packet-size               (.getMaximumPacketSize params)
   :need-client-auth?                 (.getNeedClientAuth params)
   :protocols                         (.getProtocols params)
   :server-names                      (.getServerNames params)
   :sni-matchers                      (.getSNIMatchers params)
   :use-cipher-suites-order?          (.getUseCipherSuitesOrder params)
   :want-client-auth?                 (.getWantClientAuth params)})

(defn- get-key-store [^String key-store-file password-chars]
  (with-open [in (FileInputStream. key-store-file)]
    (doto (KeyStore/getInstance "JKS")
      (.load in password-chars))))

(defn- get-key-manager-array []
  (let [key-store-file      (System/getProperty "javax.net.ssl.keyStore")
        password            (System/getProperty "javax.net.ssl.keyStorePassword")
        password-chars      (into-array Character/TYPE password)
        key-store           (get-key-store key-store-file password-chars)
        key-manager-factory (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                              (.init key-store password-chars))]
    (.getKeyManagers key-manager-factory)))

(defn- get-trust-manager-array []
  (into-array TrustManager
              [(reify X509TrustManager
                 (getAcceptedIssuers [this] (make-array X509Certificate 0))
                 (checkClientTrusted [this certs auth-type])
                 (checkServerTrusted [this certs auth-type]))]))

(defn- get-trusting-ssl-context []
  (doto (SSLContext/getInstance "TLS")
    (.init (get-key-manager-array)
           (get-trust-manager-array)
           (SecureRandom.))))

(defn- create-ssl-socket [port]
  (let [^SSLContext ssl-context (get-trusting-ssl-context)
        ^SSLServerSocket server-socket (-> ssl-context
                                           (.getServerSocketFactory)
                                           (.createServerSocket port))]
    (doto server-socket
      (.setWantClientAuth true))))

(defn- sha256-hash [bytes]
  (as-> (MessageDigest/getInstance "SHA-256") %
    (.digest % bytes)
    (BigInteger. 1 %)
    (.toString % 16)))

(defn- get-client-certificate [^SSLSession session]
  (when-let [certificate-chain (try (.getPeerCertificates session) (catch Exception _ nil))]
    (let [^X509Certificate client-cert (first certificate-chain)]
      {:type                       (.getType client-cert)
       :version                    (.getVersion client-cert)
       :serial-number              (.getSerialNumber client-cert)
       :not-before                 (.getNotBefore client-cert)
       :not-after                  (.getNotAfter client-cert)
       :subject-distinguished-name (.getName (.getSubjectX500Principal client-cert))
       :subject-alternative-names  (seq (.getSubjectAlternativeNames client-cert))
       :issuer-distinguished-name  (.getName (.getIssuerX500Principal client-cert))
       :issuer-alternative-names   (seq (.getIssuerAlternativeNames client-cert))
       :sha256-hash                (sha256-hash (.getEncoded client-cert))})))

(defn- read-socket! [^SSLSocket socket]
  (let [session (.getSession socket)
        request (parse-uri (.readLine ^BufferedReader (io/reader socket)))]
    (assoc request :client-cert (get-client-certificate session))))

(defn- write-socket! [^SSLSocket socket {:keys [status meta body]}]
  (doto (io/writer socket)
    (.write (str status " " meta "\r\n"))
    (.flush))
  (when body
    (with-open [in-stream (io/input-stream (if (string? body) (.getBytes ^String body) body))]
      (let [out-stream (io/output-stream socket)]
        (.transferTo in-stream out-stream)
        (.flush out-stream))))
  (.shutdownOutput socket))

;; FIXME: Only accept connections passing an SNI HostName that matches (System/getProperty "sni.hostname")
(defn- accept-connections! [^SSLServerSocket server-socket document-root]
  (while @global-server-thread
    (try
      (let [socket (.accept server-socket)]
        (try
          (->> (read-socket! socket)
               (gemini-handler document-root)
               (write-socket! socket))
          (catch Exception e
            (log "Server error:" e))
          (finally (.close socket))))
      (catch Exception _))))

(defn- stop-server! []
  (if @global-server-thread
    (do
      (reset! global-server-thread nil)
      (when @global-server-socket
        (.close ^SSLServerSocket @global-server-socket)
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
                  (with-open [^SSLServerSocket server-socket (create-ssl-socket port)]
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
    "Missing sni.hostname property. Please set this in deps.edn."

    (nil? (System/getProperty "javax.net.ssl.keyStore"))
    "Missing javax.net.ssl.keyStore property. Please set this in deps.edn."

    (nil? (System/getProperty "javax.net.ssl.keyStorePassword"))
    "Missing javax.net.ssl.keyStorePassword property. Please set this in deps.edn."))

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

;; FIXME: Use clojure.tools.cli/parse-opts to provide actual command line switches
(defn -main [& [document-root port]]
  (when-not @global-server-thread ; Prevents server freezing attacks from user scripts
    (println program-banner)
    (if-let [error-msg (check-inputs document-root port)]
      (log error-msg)
      (do
        (start-server! document-root (get-int-port port))
        (deref @global-server-thread)))
    (shutdown-agents)))
