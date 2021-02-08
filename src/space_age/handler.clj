;; FIXME: Harden this namespace by making all def'ed symbols private if possible
(ns space-age.handler
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.mime-types :refer [get-extension get-mime-type]]
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

;; FIXME: Reject any scripts containing calls to these functions: (ns in-ns remove-ns shutdown-agents System/exit load-mime-types!)
(defn run-clj-script [^File file request]
  (let [script-ns-name (gensym "script")]
    (try
      (binding [*ns* (create-ns script-ns-name)]
        (refer-clojure)
        (with-out-str (load-file (.getPath file))) ; mute script's stdout
        (if-let [main-fn (resolve 'main)]
          (let [response (main-fn request)]
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

(defn ensure-clj-extension [file-path]
  (if (str/ends-with? file-path ".clj")
    file-path
    (str file-path ".clj")))

(defn check-for-script [directory file-path]
  (let [possible-script-file (io/file directory (ensure-clj-extension file-path))]
    (when (and (.isFile possible-script-file)
               (.canRead possible-script-file)
               (.canExecute possible-script-file))
      possible-script-file)))

(defn script-scan [directory path]
  (when-not (str/blank? path)
    (let [path-segments (str/split path #"/")]
      (loop [path-thus-far           (first path-segments)
             remaining-path-segments (rest path-segments)]
        (if-let [script-file (check-for-script directory path-thus-far)]
          {:script-file script-file
           :script-path path-thus-far
           :path-args   (vec remaining-path-segments)}
          (when (seq remaining-path-segments)
            (recur (str path-thus-far "/" (first remaining-path-segments))
                   (rest remaining-path-segments))))))))

;; FIXME: This is pretty ugly code. Make it more elegant.
(defn path->file [document-root path]
  (if-let [[_ user file-path] (re-find #"^/~([^/]+)/?(.*)$" path)]
    (let [env-home  (System/getenv "HOME")
          env-user  (System/getenv "USER")
          user-home (if (and env-home env-user)
                      (str/replace env-home env-user user)
                      (str "/home/" user))]
      (if-let [script-info (script-scan (io/file user-home "public_gemini") file-path)]
        [{:script-file script-info}
         (str "/~" user "/" {:script-path script-info})
         {:path-args script-info}]
        [(io/file user-home "public_gemini" file-path)]))
    (if-let [script-info (script-scan (io/file document-root) (subs path 1))]
      [{:script-file script-info}
       (str "/" {:script-path script-info})
       {:path-args script-info}]
      [(io/file document-root (subs path 1))])))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links. If an executable
;; *.clj file is requested, it is loaded into its own temporary
;; namespace and its "main" function (if any) is run. If the return
;; value is a valid Gemini response, it is returned to the client.
;; Otherwise, an error response is returned.
(defn process-request [document-root {:keys [path raw-path raw-query] :as request}]
  (try
    (let [[^File file script-path path-args] (path->file document-root path)]
      (if (and (.isFile file) (.canRead file))
        (let [filename (.getName file)]
          (if (and (= "clj" (get-extension filename)) (.canExecute file))
            (run-clj-script file (-> request
                                     (assoc :script-path script-path)
                                     (update :params #(into path-args %))))
            (success-response (get-mime-type filename) file)))
        (if (and (.isDirectory file) (.canRead file))
          (if-not (str/ends-with? path "/")
            (permanent-redirect-response (str raw-path "/" (when raw-query "?") raw-query))
            (if-let [^File index-file (->> ["index.gmi" "index.gemini"]
                                           (map #(io/file file %))
                                           (filter #(and (.isFile ^File %) (.canRead ^File %)))
                                           (first))]
              (success-response (get-mime-type (.getName index-file)) index-file)
              (success-response (get-mime-type "directory.gmi")
                                (make-directory-listing path file))))
          (if (.exists file)
            (permanent-failure-response "File exists but is not readable.")
            (not-found-response "File not found.")))))
    (catch Exception e
      (temporary-failure-response (str "Error processing request: " (.getMessage e))))))

;; Example URI: gemini://myhost.org/foo/bar.clj?baz&buzz&bazizzle\r\n
;; FIXME: Return status code 53 if host and port do not match expected values for this server
(defn gemini-handler [document-root {:keys [uri parse-error? scheme path] :as request}]
  (log uri)
  (cond parse-error?
        (bad-request-response "Malformed URI.")

        (not= scheme "gemini")
        (bad-request-response (str "Protocol \"" scheme "\" is not supported."))

        (str/includes? path "/..")
        (bad-request-response "Paths may not contain /.. elements.")

        :else
        (process-request document-root request)))
