(ns space-age.db
  (:refer-clojure :exclude [distinct filter for group-by into partition-by set update])
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            ;; [honey.sql :as sql]
            ;; [honey.sql.helpers :refer :all :as h]
            [clojure.core :as c]))


(def chat {:dbtype "sqlite" :dbname "data/chat.db"})

(def ds_chat (jdbc/get-datasource chat))


(defn init-chat-db []
  (do
    (jdbc/execute! ds_chat ["drop table if exists messages"])
    (jdbc/execute! ds_chat
                   ["create table messages (id int auto_increment primary key,
                                                username varchar(255),
                                                message varchar(255),
                                                time varchar(255))"])
    (jdbc/execute! ds_chat ["drop table if exists users"])
    (jdbc/execute! ds_chat
                   ["create table users (id int auto_increment primary key,
                                             username varchar(255),
                                             cert varchar(255), 
                                             joined datetime default current_timestamp)"])))

(comment
  (init-chat-db))

(defn chat-insert-message! [message]
  (sql/insert! ds_chat :messages message))

(defn chat-get-messages []
  (sql/query ds_chat ["select * from messages"]))

(defn chat-register-user! [cert name]
  (sql/insert! ds_chat :users {:cert cert :username name}))

(defn chat-update-name! [cert name]
  (sql/update! ds_chat :users {:username name} {:cert cert}))

(defn chat-get-username [cert]
  (->
   (sql/query ds_chat ["select username from users where cert = ?" cert])
   first
   :users/username))

