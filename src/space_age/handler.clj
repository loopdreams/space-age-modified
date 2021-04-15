;; TODO: Harden this namespace by making all def'ed symbols private if possible
(ns space-age.handler
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.mime-types :refer [get-mime-type]]
            [space-age.responses :refer [success-response
                                         permanent-redirect-response
                                         temporary-failure-response
                                         cgi-error-response
                                         permanent-failure-response
                                         not-found-response
                                         bad-request-response]]))

(def valid-status-codes #{10 11 20 30 31 40 41 42 43 44 50 51 52 53 59 60 61 62})

(defn valid-response? [response]
  (and (map? response)
       (contains? valid-status-codes (:status response))
       (or (string? (:meta response))
           (integer? (:meta response)))
       (or (nil? (:body response))
           (string? (:body response))
           (instance? File (:body response)))))

(defn clean-request [{:keys [path-args] :as request}]
  (-> request
      (update :params #(into path-args %))
      (dissoc :document-root
              :search-path-prefix
              :search-path
              :script-file
              :path-args
              :target-file
              :target-type)))

;; TODO: Reject any scripts containing calls to these functions: (ns in-ns remove-ns shutdown-agents System/exit load-mime-types!)
(defn run-clj-script [^File file request]
  (let [script-ns-name (gensym "script")]
    (try
      (binding [*ns* (create-ns script-ns-name)]
        (refer-clojure)
        (with-out-str (load-file (.getPath file))) ; mute script's stdout
        (if-let [main-fn (resolve 'main)]
          (let [response (main-fn (clean-request request))]
            (if (valid-response? response)
              response
              (cgi-error-response "Script error: Malformed response.")))
          (cgi-error-response "Script error: No main function.")))
      (catch Exception e
        (cgi-error-response (str "Script error: " (.getMessage e))))
      (finally (remove-ns script-ns-name)))))

(defn make-directory-listing [path ^File directory]
  (->> (.listFiles directory)
       (map #(str "=> " (.getName ^File %) (when (.isDirectory ^File %) "/") "\n"))
       (sort)
       (str/join)
       (str "Directory Listing: " path "\n\n")))

(defn generate-response [{:keys [^File target-file target-type path raw-path raw-query] :as request}]
  (case target-type
    :script               (run-clj-script target-file request)
    :file                 (success-response (get-mime-type (.getName target-file)) target-file)
    :directory            (success-response (get-mime-type "directory.gmi") (make-directory-listing path target-file))
    :directory-no-slash   (permanent-redirect-response (str raw-path "/" (when raw-query "?") raw-query))
    :unreadable-file      (permanent-failure-response "File exists but is not readable.")
    :unreadable-directory (permanent-failure-response "Directory exists but is not readable.")
    :file-not-found       (not-found-response "File not found.")))

(defn check-for-index [directory]
  (->> ["index.gmi" "index.gemini"]
       (map #(io/file directory %))
       (filter #(and (.isFile ^File %) (.canRead ^File %)))
       (first)))

(defn get-file-type [^File file]
  (cond (.isFile file)      (if (.canRead file)
                              :file
                              :unreadable-file)
        (.isDirectory file) (if (.canRead file)
                              :directory
                              :unreadable-directory)
        :else               :file-not-found))

(defn identify-target-file [{:keys [document-root search-path script-file path] :as request}]
  (if script-file
    (assoc request
           :target-file script-file
           :target-type :script)
    (let [target-file (io/file document-root search-path)
          target-type (get-file-type target-file)
          index-file  (when (= target-type :directory) (check-for-index target-file))]
      (cond-> (assoc request
                     :target-file target-file
                     :target-type target-type)

        (and (= target-type :directory) (not (str/ends-with? path "/")))
        (assoc :target-type :directory-no-slash)

        index-file
        (assoc :target-file index-file :target-type :file)))))

(defn ensure-clj-extension [search-path]
  (if (str/ends-with? search-path ".clj")
    search-path
    (str search-path ".clj")))

(defn check-for-script [document-root search-path]
  (let [possible-script-files (cond->> (list (io/file document-root search-path "index.clj"))
                                (seq search-path)
                                (cons (io/file document-root (ensure-clj-extension search-path))))]
    (first
     (filter #(and (.isFile %)
                   (.canRead %)
                   (.canExecute %))
             possible-script-files))))

(defn scan-for-scripts [{:keys [document-root search-path-prefix search-path] :as request}]
  (let [path-segments (str/split search-path #"/")]
    (loop [path-thus-far           (first path-segments)
           remaining-path-segments (rest path-segments)]
      (if-let [script-file (check-for-script document-root path-thus-far)]
        (assoc request
               :script-file script-file
               :script-path (str search-path-prefix path-thus-far)
               :path-args   (vec remaining-path-segments))
        (if (seq remaining-path-segments)
          (recur (str path-thus-far "/" (first remaining-path-segments))
                 (rest remaining-path-segments))
          request)))))

(defn get-home-dir [user]
  (let [env-home (System/getenv "HOME")
        env-user (System/getenv "USER")]
    (if (and env-home env-user)
      (str/replace env-home env-user user)
      (str "/home/" user))))

(defn identify-search-path [{:keys [path] :as request} document-root]
  (if-let [[_ user search-path] (re-find #"^/~([^/]+)/?(.*)$" path)]
    (assoc request
           :document-root      (.getPath (io/file (get-home-dir user) "public_gemini"))
           :search-path-prefix (str "/~" user "/")
           :search-path        search-path)
    (assoc request
           :document-root      document-root
           :search-path-prefix "/"
           :search-path        (subs path 1))))

;; TODO: Return status code 53 if host and port do not match expected values for this server
(defn check-request [{:keys [parse-error? scheme path]}]
  (cond parse-error?               "Malformed URI."
        (not= scheme "gemini")     (format "Protocol \"%s\" is not supported." scheme)
        (str/includes? path "/..") "Paths may not contain /.. elements."))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links. If an executable
;; *.clj file is requested, it is loaded into its own temporary
;; namespace and its "main" function (if any) is run. If the return
;; value is a valid Gemini response, it is returned to the client.
;; Otherwise, an error response is returned.
(defn gemini-handler [document-root request]
  (log (:uri request))
  (if-let [error-msg (check-request request)]
    (bad-request-response error-msg)
    (try
      (-> request
          (identify-search-path document-root)
          (scan-for-scripts)
          (identify-target-file)
          (generate-response))
      (catch Exception e
        (temporary-failure-response (str "Error processing request: " (.getMessage e)))))))
