(ns space-age.db
  (:refer-clojure :exclude [distinct filter for group-by into partition-by set update])
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            ;; [honey.sql :as sql]
            ;; [honey.sql.helpers :refer :all :as h]
            [clojure.core :as c]))

(def users {:dbtype "sqlite" :dbname "data/users.db"})

(def messages {:dbtype "sqlite" :dbname "data/messages.db"})

(def ds_user (jdbc/get-datasource users))
(def ds_messages (jdbc/get-datasource messages))

;; TODO proper initialisation of db
(comment
  (jdbc/execute! ds_user
                 ["create table users (id int auto_increment primary key,
                                       name varchar(32),
                                       age int)"])

  (jdbc/execute! ds_messages
                 ["create table messages (id int auto_increment primary key,
                                              user_name varchar(32),
                                              message varchar(255),
                                              time varchar(255))"]))

(defn insert-message! [{:keys [user-name message time]}]
  (sql/insert! ds_messages :messages {:user_name user-name
                                      :message   message
                                      :time      time}))

(defn get-messages []
  (sql/query ds_messages ["select * from messages"]))

