(ns space-age.user-registration
  (:require [space-age.db :as db]))


(defn name-in-use? [input]
  ((db/all-users) input))

(defn register-user []
  {:status 60
   :meta "Please attach your client certificate"})

(defn register-name [req source]
  (let [input (:query req)]
    (if-not input
      {:status 10 :meta "Enter name"}

      (if (name-in-use? input)
        {:status 10 :meta "This name is already in use, please try another"}
        (do (db/register-user! (db/client-id req) (:query req))
            {:status 30 :meta source})))))

(defn update-name [req source]
  (let [input (:query req)]
    (if-not input
      {:status 10 :meta "Enter new name"}

      (if (name-in-use? input)
        {:status 10 :meta "This name is already in use, please try another"}
        (do (db/update-name! (db/client-id req) (:query req))
            {:status 30 :meta source})))))
