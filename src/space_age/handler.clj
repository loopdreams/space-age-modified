(ns space-age.handler
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.mime-types :refer [get-extension get-mime-type]]
            [space-age.responses :refer [success-response
                                         redirect-response
                                         temporary-failure-response
                                         permanent-failure-response]]))

(defn valid-response? [response]
  (and (map? response)
       (contains? #{10 20 30 40 50 60} (:status response))
       (string? (:meta response))
       (or (nil? (:body response))
           (string? (:body response))
           (instance? File (:body response)))))

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
              (permanent-failure-response "Script error: Invalid response.")))
          (permanent-failure-response "Script error: No main function.")))
      (catch Exception e
        (permanent-failure-response (str "Script error: " (.getMessage e))))
      (finally (remove-ns script-ns-name)))))

(defn make-directory-listing [path ^File directory]
  (->> (.listFiles directory)
       (map #(str "=> " (.getName %) (when (.isDirectory %) "/") "\n"))
       (sort)
       (str/join)
       (str "Directory Listing: " path "\n\n")))

(defn path->file [document-root path]
  (if-let [[_ user file-path] (re-find #"^/~([^/]+)/?(.*)$" path)]
    (let [env-home  (System/getenv "HOME")
          env-user  (System/getenv "USER")
          user-home (if (and env-home env-user)
                      (str/replace env-home env-user user)
                      (str "/home/" user))]
      (io/file user-home "public_gemini" file-path))
    (io/file document-root (subs path 1))))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links. If an executable
;; *.clj file is requested, it is loaded into its own temporary
;; namespace and its "main" function (if any) is run. If the return
;; value is a valid Gemini response, it is returned to the client.
;; Otherwise, an error response is returned.
(defn process-request [document-root {:keys [path raw-path raw-query raw-fragment] :as request}]
  (try
    (let [file (path->file document-root path)]
      (if (and (.isFile file) (.canRead file))
        (let [filename (.getName file)]
          (if (and (= "clj" (get-extension filename)) (.canExecute file))
            (run-clj-script file request)
            (success-response (get-mime-type filename) file)))
        (if (and (.isDirectory file) (.canRead file))
          (if-not (str/ends-with? path "/")
            (redirect-response (str raw-path "/" (when raw-query "?") raw-query (when raw-fragment "#") raw-fragment))
            (if-let [index-file (->> ["index.gmi" "index.gemini"]
                                     (map #(io/file file %))
                                     (filter #(and (.isFile %) (.canRead %)))
                                     (first))]
              (success-response (get-mime-type (.getName index-file)) index-file)
              (success-response (get-mime-type "directory.gmi")
                                (make-directory-listing path file))))
          (if (.exists file)
            (permanent-failure-response "File exists but is not readable.")
            (permanent-failure-response "File not found.")))))
    (catch Exception e
      (temporary-failure-response (str "Error processing request: " (.getMessage e))))))

;; Example URI: gemini://myhost.org/foo/bar.clj?baz&buzz&bazizzle\r\n
;; FIXME: Return error if host and port do not match expected values
;; FIXME: If scheme is empty, return "59 Bad Request\r\n"
(defn gemini-handler [document-root {:keys [uri parse-error? scheme path] :as request}]
  (log uri)
  (cond parse-error?
        (permanent-failure-response "Malformed URI.")

        (not= scheme "gemini")
        (permanent-failure-response (str "Protocol \"" scheme "\" is not supported."))

        (str/includes? path "/..")
        (permanent-failure-response "Paths may not contain /.. elements.")

        :else
        (process-request document-root request)))
