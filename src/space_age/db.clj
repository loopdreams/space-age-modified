(ns space-age.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [java-time.api :as jt]))

(def db_games (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/games.db"}))

;; word list from https://github.com/charlesreid1/five-letter-words/blob/master/sgb-words.txt
(defonce wordle-words
  (-> (slurp "resources/sgb-words.txt")
      (str/split #"\n")
      shuffle))

(defn drop-table! [table]
  (jdbc/execute! db_games [(str "DROP TABLE IF EXISTS " table)]))

(defn create-table! [spec]
  (jdbc/execute! db_games spec))

(defn init-dbs! []
  (doall
   (map drop-table!
        ["users" "messages" "wordlegames" "wordlewords" "chessgames"]))
  (map create-table!
       [["create table users (userid integer not null primary key,
                                           username varchar(255),
                                           cert varchar(255),
                                           joined datetime default current_timestamp)"]
        ["create table messages (msgid integer not null primary key,
                                              username varchar(255),
                                              message varchar(255),
                                              time varchar(255))"]
        ["create table wordlegames (gameid integer not null primary key,
                                                 gamedate datetime default current_timestamp,
                                                 uid varchar(255),
                                                 guesses varchar(255),
                                                 score integer,
                                                 win integer default 0)"]
        ["create table wordlewords (wordid integer not null primary key,
                                                 word varchar(10),
                                                 day datetime)"]
        ["create table chessgames (rowid integer not null primary key,
                                                gameid integer,
                                                startdate datetime default current_timestamp,
                                                startedby varchar(255),
                                                enddate datetime,
                                                playerturn varchar(10) default 'white',
                                                whiteID varchar(255),
                                                blackID varchar(255),
                                                boardstate varchar(255),
                                                complete integer default 0,
                                                winner varchar(255),
                                                winnerID varchar(255))"]]))
        

(defn init-words! [word-list]
  (let [start-date (atom (jt/local-date))]
    (for [w word-list]
      (do
        (sql/insert! db_games :wordlewords {:word w :day (str @start-date)})
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
  (sql/insert! db_games :users {:cert cert :username name}))

(defn update-name! [cert name]
  (sql/update! db_games :users {:username name} {:cert cert}))

(defn get-username [req]
  (let [cert (client-id req)]
    (->
     (sql/query db_games ["select username from users where cert = ?" cert])
     first
     :users/username)))

(defn get-username-by-id [id]
  (-> (sql/query db_games ["SELECT username FROM users WHERE cert = ?" id])
      first
      :users/username))

(defn all-users []
  (->> (sql/query db_games ["select username from users"])
       (map :users/username)
       (into #{})))

;; CHAT
(defn chat-insert-message! [message]
  (sql/insert! db_games :messages message))

(defn chat-get-messages []
  (sql/query db_games ["select * from messages"]))


;; WORDLE

(defonce valid-words
  (->> (sql/query db_games ["SELECT word FROM wordlewords"])
      (map :wordlewords/word)
      (into #{})))

(defn get-todays-word []
  (-> (sql/query db_games ["SELECT word FROM wordlewords WHERE day = strftime('%Y-%m-%d', date('now'))"])
      first
      :wordlewords/word))

(defn get-today-q [col req]
  (let [uid (client-id req)]
    [(str "SELECT " col
          " FROM wordlegames WHERE uid = ? AND strftime('%Y-%m-%d', gamedate) = strftime('%Y-%m-%d', date('now'))")
     uid]))

(defn get-guesses [req]
  (-> (sql/query db_games (get-today-q "guesses" req))
      first
      :wordlegames/guesses))

(defn get-game-id [req]
  (-> (sql/query db_games (get-today-q "gameid" req))
      first
      :wordlegames/gameid))

(defn get-score [req]
  (-> (sql/query db_games (get-today-q "score" req))
      first
      :wordlegames/score))

(defn win-condition [req]
  (-> (sql/query db_games (get-today-q "win" req))
      first
      :wordlegames/win))

(defn update-win-condition! [req]
  (sql/update! db_games :wordlegames {:win 1} {:gameid (get-game-id req)}))

(defn insert-guess! [req guess-str]
  (if (get-guesses req)
    (let [game-id (get-game-id req)]
      (sql/update! db_games :wordlegames {:guesses guess-str} {:gameid game-id})
      (jdbc/execute! db_games ["UPDATE wordlegames SET score = score + 1 WHERE gameid = ?" game-id]))
    (sql/insert! db_games :wordlegames {:uid (client-id req) :guesses guess-str :score 1})))

(defn user-stats [req]
  (sql/query db_games ["SELECT * FROM wordlegames WHERE uid = ?" (client-id req)]))


;; CHESS

(defn init-game [req board colour gameid]
  (let [uid (client-id req)]
    (sql/insert! db_games :chessgames {colour uid
                                       :boardstate board
                                       :gameid gameid
                                       :startedby uid})))

(defn player-join [req gameid colour]
  (sql/update! db_games :chessgames {colour (client-id req)} {:gameid gameid}))




(defn get-gameinfo [gameid]
  (sql/query db_games ["SELECT * FROM chessgames WHERE gameid = ?" gameid]))

;; TODO set enddate on win
(defn update-board! [gameid board win?]
  (let [{:chessgames/keys [playerturn whiteID blackID]} (first (get-gameinfo gameid))
        next-player (if (= playerturn "white")
                      "black"
                      "white")]
    (do
      (sql/update! db_games :chessgames {:boardstate board :playerturn next-player} {:gameid gameid})
      (when win?
        (let [winner-colour (if (= next-player "white") "black" "white")
              winner-id (if (= winner-colour "white") whiteID blackID)]
          (sql/update! db_games :chessgames
                       {:complete 1
                        :winner winner-colour
                        :winnerID winner-id}
                       {:gameid gameid}))))))

(defn get-active-games [req]
  (let [uid              (client-id req)
        all-active-games (sql/query db_games ["SELECT * FROM chessgames WHERE complete = 0"])
        player-games     (filter #(or (= (:chessgames/whiteID %) uid)
                                      (= (:chessgames/blackID %) uid))
                                 all-active-games)
        open-games       (->> (filter #(or (not (:chessgames/whiteID %))
                                           (not (:chessgames/blackID %)))
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
        game (first (get-gameinfo gameid))
        whiteID (:chessgames/whiteID game)
        blackID (:chessgames/blackID game)]
    (cond
      (= whiteID uid) :white
      (= blackID uid) :black
      :else "Something went wrong")))

(defn get-user-completed-games [req]
  (let [uid (client-id req)]
    (sql/query db_games ["SELECT * FROM chessgames WHERE (whiteID = ? OR blackID = ?) AND complete = 1" uid uid])))
