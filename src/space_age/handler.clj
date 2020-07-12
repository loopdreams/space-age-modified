(ns space-age.handler
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.logging :refer [log]]
            [space-age.mime-types :refer [get-extension get-mime-type]]
            [space-age.responses :refer [success-response
                                         temporary-failure-response
                                         permanent-failure-response]]))

(defn make-directory-listing [path ^File directory]
  (let [dir-name  (last (remove str/blank? (str/split path #"/")))
        dir-label (str/replace (str "/" path "/") #"/+" "/")]
    (->> (.listFiles directory)
         (map #(str "=> " dir-name "/" (.getName %) " " (.getName %)))
         (sort)
         (str/join "\n")
         (str "Directory Listing: " dir-label "\n\n"))))

(defn path->file [document-root path]
  (let [path (if (str/starts-with? path "/") (subs path 1) path)]
    (if-let [[_ user file-path] (re-find #"^~([^/]+)/?(.*)$" path)]
      (let [env-home  (System/getenv "HOME")
            env-user  (System/getenv "USER")
            user-home (if (and env-home env-user)
                        (str/replace env-home env-user user)
                        (str "/home/" user))]
        (io/file user-home "public_gemini" file-path))
      (io/file document-root path))))

(defn run-clj-script [^File file params]
  (try
    (binding [*ns* (create-ns (gensym "script"))]
      (refer-clojure)
      (intern *ns* 'request-params params)
      (let [script-output (with-out-str (load-file (.getPath file)))]
        (success-response (get-mime-type "script.gmi") script-output)))
    (catch Exception e
      (.printStackTrace e)
      (permanent-failure-response (str "Script error: " (.getMessage e))))))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links. If an executable
;; *.clj file is requested, it is run in its own temporary namespace,
;; and anything printed to standard output is returned as the body of
;; a text/gemini response.
(defn process-request [document-root {:keys [path params]}]
  (try
    (let [file (path->file document-root path)]
      (if (and (.isFile file) (.canRead file))
        (let [filename (.getName file)]
          (if (and (= "clj" (get-extension filename))
                   (.canExecute file))
            (run-clj-script file params)
            (success-response (get-mime-type filename) file)))
        (if (and (.isDirectory file) (.canRead file))
          (if-let [index-file (->> ["index.gmi" "index.gemini"]
                                   (map #(io/file file %))
                                   (filter #(and (.isFile %) (.canRead %)))
                                   (first))]
            (success-response (get-mime-type (.getName index-file)) index-file)
            (success-response (get-mime-type "directory.gmi")
                              (make-directory-listing path file)))
          (if (.exists file)
            (permanent-failure-response "File exists but is not readable.")
            (permanent-failure-response "File not found.")))))
    (catch Exception e
      (temporary-failure-response
       (str "Error processing request: " (.getMessage e))))))

;; Example URI: gemini://myhost.org/foo/bar?baz=buzz&boz=bazizzle\r\n
(defn gemini-handler [document-root request]
  (log (:uri request))
  (cond (:parse-error? request)
        (permanent-failure-response "Malformed URI.")

        (not= "gemini" (:scheme request))
        (permanent-failure-response
         (str "Protocol \"" (:scheme request) "\" is not supported."))

        :else
        (process-request document-root request)))
