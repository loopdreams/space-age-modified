;; TODO: Harden this namespace by making all def'ed symbols private if possible
(ns space-age.handler
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.mime-types :refer [get-mime-type]]
            [space-age.requests :refer [valid-request?]]
            [space-age.responses :refer [valid-response?
                                         success-response
                                         permanent-redirect-response
                                         temporary-failure-response
                                         cgi-error-response
                                         permanent-failure-response
                                         not-found-response
                                         bad-request-response]]))

(defn make-directory-listing [path ^File directory]
  (->> (.listFiles directory)
       (map #(str "=> " (.getName ^File %) (when (.isDirectory ^File %) "/") "\n"))
       (sort)
       (str/join)
       (str "Directory Listing: " path "\n\n")))

(defn generate-response [{:keys [^File target-file target-type path raw-path raw-query]}]
  (case target-type
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

(defn with-target-file [handler]
  (fn [{:keys [document-root search-path path] :as request}]
    (handler
     (let [target-file (io/file document-root search-path)
           target-type (get-file-type target-file)]
       (if (and (= target-type :directory) (not (str/ends-with? path "/")))
         (assoc request
                :target-file target-file
                :target-type :directory-no-slash)
         (if-let [index-file (when (= target-type :directory) (check-for-index target-file))]
           (assoc request
                  :target-file index-file
                  :target-type :file)
           (assoc request
                  :target-file target-file
                  :target-type target-type)))))))

(defn clean-request [{:keys [path-args] :as request}]
  (-> request
      (update :params #(into path-args %))
      (dissoc :document-root
              :search-path-prefix
              :search-path
              :path-args)))

;; TODO: Reject any scripts containing calls to these functions:
;;       (ns in-ns remove-ns shutdown-agents System/exit load-mime-types!)
(defn run-clj-script [^File file request]
  (let [script-ns-name (gensym "script")]
    (try
      (binding [*ns* (create-ns script-ns-name)]
        (refer-clojure)
        (with-out-str (load-file (.getPath file))) ; mute script's stdout
        (if-let [main-fn (resolve 'main)]
          (main-fn (clean-request request))
          (cgi-error-response "Script error: No main function.")))
      (catch Exception e
        (cgi-error-response (str "Script error: " (.getMessage e))))
      (finally (remove-ns script-ns-name)))))

(defn ensure-clj-extension [search-path]
  (if (str/ends-with? search-path ".clj")
    search-path
    (str search-path ".clj")))

(defn check-for-script [document-root search-path]
  (let [possible-script-files (cond->> (list (io/file document-root search-path "index.clj"))
                                (not= search-path "")
                                (cons (io/file document-root (ensure-clj-extension search-path))))]
    (first
     (filter #(and (.isFile ^File %)
                   (.canRead ^File %)
                   (.canExecute ^File %))
             possible-script-files))))

(defn collect-scripts [{:keys [document-root search-path-prefix search-path]}]
  (let [path-segments (cond->> (str/split search-path #"/")
                        (not= search-path "") (cons ""))]
    (loop [script-fns              []
           path-thus-far           (first path-segments)
           remaining-path-segments (rest path-segments)]
      (let [script-file (check-for-script document-root path-thus-far)]
        (if (seq remaining-path-segments)
          (recur (if script-file
                   (conj script-fns
                         #(run-clj-script script-file
                                          (assoc %
                                                 :script-path (str search-path-prefix path-thus-far)
                                                 :path-args   (vec remaining-path-segments))))
                   script-fns)
                 (if (= path-thus-far "")
                   (first remaining-path-segments)
                   (str path-thus-far "/" (first remaining-path-segments)))
                 (rest remaining-path-segments))
          script-fns)))))

(defn with-script-runner [handler]
  (fn [request]
    (loop [request                            request
           [script-fn & remaining-script-fns] (collect-scripts request)]
      (if script-fn
        (let [result (script-fn request)]
          (cond (valid-request?  result) (recur result remaining-script-fns)
                (valid-response? result) result
                :else                    (cgi-error-response "Script error: Malformed result.")))
        (handler request)))))

(defn get-home-dir [user]
  (let [env-home (System/getenv "HOME")
        env-user (System/getenv "USER")]
    (if (and env-home env-user)
      (str/replace env-home env-user user)
      (str "/home/" user))))

(defn with-search-path [handler]
  (fn [{:keys [document-root path] :as request}]
    (handler
     (if-let [[_ user search-path] (re-find #"^/~([^/]+)/?(.*)$" path)]
       (assoc request
              :document-root      (.getPath (io/file (get-home-dir user) "public_gemini"))
              :search-path-prefix (str "/~" user "/")
              :search-path        search-path)
       (assoc request
              :document-root      document-root
              :search-path-prefix "/"
              :search-path        (subs path 1))))))

;; TODO: Return status code 53 if host and port do not match expected values for this server
(defn with-request-validation [handler]
  (fn [{:keys [parse-error? scheme path] :as request}]
    (if-let [error-msg (cond parse-error?               "Malformed URI."
                             (not= scheme "gemini")     (format "Protocol \"%s\" is not supported." scheme)
                             (str/includes? path "/..") "Paths may not contain /.. elements.")]
      (bad-request-response error-msg)
      (handler request))))

(defn with-request-logging [handler]
  (fn [request]
    (log (:uri request))
    (handler request)))

(defn with-exception-handling [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (temporary-failure-response (str "Error processing request: " (.getMessage e)))))))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links.
;;
;; If an executable *.clj file is requested (with or without the .clj
;; extension in the path), it is loaded into its own temporary
;; namespace and its "main" function (if any) is run. If the return
;; value is a valid Gemini response, it is returned to the client.
;; Otherwise, an error response is returned.
;;
;; Before either regular files, regular directories, or terminal clj
;; scripts are processed, the entire path will be scanned for
;; index.clj files. These scripts will be run in the order that they
;; are found starting from the top of the path exactly like any other
;; clj scripts. If they return a response, this will be returned to
;; the client, effectively short-circuiting the request lookup
;; process. If they return a valid request map, this new map will be
;; passed on to any other clj scripts further down the path. This
;; feature may be used for authentication or multi-stage scripting.
(def gemini-handler
  (-> generate-response
      (with-target-file)
      (with-script-runner)
      (with-search-path)
      (with-request-validation)
      (with-request-logging)
      (with-exception-handling)))
