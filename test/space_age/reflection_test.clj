(ns space-age.reflection-test
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest testing is]])
  (:import java.io.StringWriter))

(defn- get-reflection-warnings [ns-sym]
  (binding [*warn-on-reflection* true
            *err*                (StringWriter.)]
    (require ns-sym :reload-all)
    (str/split-lines (str *err*))))

(defn- ns-symbol->java-package-root [ns-sym]
  (-> ns-sym
      (name)
      (str/split #"\.")
      (first)
      (str/replace "-" "_")))

(defn- get-local-reflection-warnings [ns-sym]
  (let [warnings (get-reflection-warnings ns-sym)
        sym-base (ns-symbol->java-package-root ns-sym)]
    (filterv (fn [s] (and (str/starts-with? s "Reflection warning")
                          (str/includes? s sym-base)))
             warnings)))

(deftest check-reflection
  (testing "Checking for reflection warnings..."
    (is (empty? (get-local-reflection-warnings 'space-age.server)))))
