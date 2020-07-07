(ns space-age.responses
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [space-age.mime-types :refer [get-mime-type]]))

(defn input-response [prompt]
  (str "10 " prompt "\r\n"))

;; body can be either a string or a java.io.File
(defn success-response [type body]
  [(str "20 " type "\r\n") body])

(defn redirect-response [uri]
  (str "30 " uri "\r\n"))

(defn temporary-failure-response [msg]
  (str "40 " msg "\r\n"))

(defn permanent-failure-response [msg]
  (str "50 " msg "\r\n"))

;; FIXME: Implement client certificates
(defn client-certificate-required-response [msg]
  (str "60 " msg "\r\n"))

(defn make-directory-listing [route ^File directory]
  (let [route (if (str/ends-with? route "/") route (str route "/"))]
    (->> (.listFiles directory)
         (map #(str "=> " route (.getName %) " " (.getName %)))
         (sort)
         (str/join "\n")
         (str "Directory Listing: " route "\n\n"))))

(defn route->file [document-root route]
  (if-let [[_ user file-path] (re-find #"^~([^/]+)/?(.*)$" route)]
    (let [user-home (str/replace (System/getenv "HOME")
                                 (System/getenv "USER")
                                 user)]
      (io/file user-home file-path))
    (io/file document-root route)))

;; Currently we serve up any readable file under a user's home
;; directory or the document-root directory. If a directory is
;; requested, we serve up its index.gmi or index.gemini if available.
;; Otherwise, we make a directory listing of links.
(defn process-request [document-root {:keys [route params]}]
  (try
    (let [file (route->file document-root route)]
      (if (and (.isFile file) (.canRead file))
        (success-response (get-mime-type (.getName file)) file)
        (if (and (.isDirectory file) (.canRead file))
          (if-let [index-file (->> ["index.gmi" "index.gemini"]
                                   (map #(io/file file %))
                                   (filter #(and (.isFile %) (.canRead %)))
                                   (first))]
            (success-response (get-mime-type (.getName index-file)) index-file)
            (success-response (get-mime-type "directory.gmi")
                              (make-directory-listing route file)))
          (if (.exists file)
            (permanent-failure-response "File exists but is not readable.")
            (permanent-failure-response "File not found.")))))
    (catch Exception e (temporary-failure-response (str "Server error: " (.getMessage e))))))
