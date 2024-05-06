(ns space-age.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [java-time.api :as jt]))

(def db_users (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/users.db"}))
(def db_chat (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/chat.db"}))
(def db_wordle (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/wordle.db"}))
(def db_chess (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/chess.db"}))

;; word list from https://github.com/charlesreid1/five-letter-words/blob/master/sgb-words.txt
(defonce wordle-words
  (-> (slurp "resources/sgb-words.txt")
      (str/split #"\n")
      shuffle))

(defn init-dbs! []
  (jdbc/execute! db_users ["drop table if exists users"])
  (jdbc/execute! db_chat ["drop table if exists messages"])
  (jdbc/execute! db_wordle ["drop table if exists games"])
  (jdbc/execute! db_wordle ["drop table if exists words"])
  (jdbc/execute! db_chess ["drop table if exists games"])
  (jdbc/execute! db_users
                 ["create table users (userid integer not null primary key,
                                           username varchar(255),
                                           cert varchar(255),
                                           joined datetime default current_timestamp)"])
  (jdbc/execute! db_chat
                 ["create table messages (msgid integer not null primary key,
                                              username varchar(255),
                                              message varchar(255),
                                              time varchar(255))"])
  (jdbc/execute! db_wordle
                 ["create table games (gameid integer not null primary key,
                                           gamedate datetime default current_timestamp,
                                           uid varchar(255),
                                           guesses varchar(255),
                                           score integer,
                                           win integer default 0)"])
  (jdbc/execute! db_wordle
                 ["create table words (wordid integer not null primary key,
                                           word varchar(10),
                                           day datetime)"])
  (jdbc/execute! db_chess
                 ["create table games (rowid integer not null primary key,
                                           gameid integer,
                                           startdate datetime default current_timestamp,
                                           startedby varchar(255),
                                           enddate datetime,
                                           playerturn varchar(10) default 'white',
                                           whiteID varchar(255),
                                           blackID varchar(255),
                                           boardstate varchar(255),
                                           complete integer default 0,
                                           winner varchar(255))"]))

(defn init-words! [word-list]
  (let [start-date (atom (jt/local-date))]
    (for [w word-list]
      (do
        (sql/insert! db_wordle :words {:word w :day (str @start-date)})
        (swap! start-date #(jt/plus % (jt/days 1)))
        nil))))

(comment
  (init-dbs!))

(comment
  (init-words! (take 5 wordle-words)))

;; Utility
(defn client-id [req]
  (-> req
      :client-cert
      :sha256-hash))

;; USERS
(defn register-user! [cert name]
  (sql/insert! db_users :users {:cert cert :username name}))

(defn update-name! [cert name]
  (sql/update! db_users :users {:username name} {:cert cert}))

(defn get-username [req]
  (let [cert (client-id req)]
    (->
     (sql/query db_users ["select username from users where cert = ?" cert])
     first
     :users/username)))

(defn get-username-by-id [id]
  (-> (sql/query db_users ["SELECT username FROM users WHERE cert = ?" id])
      first
      :users/username))

(defn all-users []
  (->> (sql/query db_users ["select username from users"])
       (map :users/username)
       (into #{})))

;; CHAT
(defn chat-insert-message! [message]
  (sql/insert! db_chat :messages message))

(defn chat-get-messages []
  (sql/query db_chat ["select * from messages"]))


;; WORDLE

(defn get-todays-word []
  (-> (sql/query db_wordle ["SELECT word FROM words WHERE day = strftime('%Y-%m-%d', date('now'))"])
      first
      :words/word))

(defn get-today-q [col req]
  (let [uid (client-id req)]
    [(str "SELECT " col
          " FROM games WHERE uid = ? AND strftime('%Y-%m-%d', gamedate) = strftime('%Y-%m-%d', date('now'))")
     uid]))

(defn get-guesses [req]
  (-> (sql/query db_wordle (get-today-q "guesses" req))
      first
      :games/guesses))

(defn get-game-id [req]
  (-> (sql/query db_wordle (get-today-q "gameid" req))
      first
      :games/gameid))

(defn get-score [req]
  (-> (sql/query db_wordle (get-today-q "score" req))
      first
      :games/score))

(defn win-condition [req]
  (-> (sql/query db_wordle (get-today-q "win" req))
      first
      :games/win))

(defn update-win-condition! [req]
  (sql/update! db_wordle :games {:win 1} {:gameid (get-game-id req)}))

(defn insert-guess! [req guess-str]
  (if (get-guesses req)
    (let [game-id (get-game-id req)]
      (sql/update! db_wordle :games {:guesses guess-str} {:gameid game-id})
      (jdbc/execute! db_wordle ["UPDATE games SET score = score + 1 WHERE gameid = ?" game-id]))
    (sql/insert! db_wordle :games {:uid (client-id req) :guesses guess-str :score 1})))

(defn user-stats [req]
  (sql/query db_wordle ["SELECT * FROM games WHERE uid = ?" (client-id req)]))


;; CHESS

(defn init-game [req board colour gameid]
  (let [uid (client-id req)]
    (sql/insert! db_chess :games {colour uid
                                  :boardstate board
                                  :gameid gameid
                                  :startedby uid})))

(defn player-join [req gameid colour]
  (sql/update! db_chess :games {colour (client-id req)} {:gameid gameid}))

(defn update-board [gameid board]
  (sql/update! db_chess :games {:boardstate board} {:gameid gameid}))

(defn get-gameinfo [gameid]
  (sql/query db_chess ["SELECT * FROM games WHERE gameid = ?" gameid]))

(defn get-active-games [req]
  (let [uid              (client-id req)
        all-active-games (sql/query db_chess ["SELECT * FROM games WHERE complete = 0"])
        player-games     (filter #(or (= (:games/whiteID %) uid)
                                      (= (:games/blackID %) uid))
                                 all-active-games)
        open-games       (->> (filter #(or (not (:games/whiteID %))
                                           (not (:games/blackID %)))
                                      all-active-games)
                              (remove #(some #{%} player-games)))
        running-games    (->> all-active-games
                              (remove #(some #{%} player-games))
                              (remove #(some #{%} open-games)))]
    {:player-games  player-games
     :open-games    open-games
     :running-games running-games}))

(defn get-player-type [req gameid]
  (let [uid (client-id req)
        game (get-gameinfo gameid)
        whiteID (:games/whiteID game)
        blackID (:games/blackID game)]
    (cond = uid
          whiteID :white
          blackID :black
          :else "Something went wrong")))

(comment
  (insert-guess! "Johnny" "yoyo")
  (get-game-id "Mary")
  (get-guesses "Mark")
  (get-game-id "John")
  (get-todays-word)
  (get-active-games nil))
