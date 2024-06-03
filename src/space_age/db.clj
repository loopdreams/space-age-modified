(ns space-age.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [java-time.api :as jt]))

(def db_games (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/games.db"}))

;; word list from https://github.com/charlesreid1/five-letter-words/blob/master/sgb-words.txt
(defn wordle-words []
  (-> (slurp "resources/sgb-words.txt")
      (str/split #"\n")
      shuffle))

(defn drop-table! [table]
  (jdbc/execute! db_games [(str "DROP TABLE IF EXISTS " table)]))

(defn create-table! [spec]
  (println "Creating table ...")
  (jdbc/execute! db_games spec))

(def users-spec ["create table users (userid integer not null primary key,
                                                         username varchar(255),
                                                         cert varchar(255),
                                                         joined datetime default current_timestamp)"])

(def messages-spec ["create table messages (msgid integer not null primary key,
                                              username varchar(255),
                                              message varchar(255),
                                              time varchar(255))"])

(def wordlgame-spec ["create table wordlegames (gameid integer not null primary key,
                                                 gamedate datetime default current_timestamp,
                                                 uid varchar(255),
                                                 guesses varchar(255),
                                                 keyboard varchar(255),
                                                 score integer,
                                                 win integer default 0)"])

(def wordlewords-spec ["create table wordlewords (wordid integer not null primary key,
                                                  word varchar(10),
                                                  day datetime)"])

(def chessgames-spec ["create table chessgames (rowid integer not null primary key,
                                                gameid integer,
                                                startdate datetime default current_timestamp,
                                                startedby varchar(255),
                                                enddate datetime,
                                                playerturn varchar(10) default 'white',
                                                whiteID varchar(255),
                                                blackID varchar(255),
                                                boardstate varchar(255),
                                                checkstate integer default 0,
                                                complete integer default 0,
                                                winner varchar(255),
                                                winnerID varchar(255),
                                                turncount integer default 1,
                                                gamemoves varchar(255),
                                                playerinput varchar(255),
                                                gamenotation varchar(255),
                                                drawstatus integer default 0,
                                                resignstatus integer default 0)"])

(defn init-dbs! [_]
  (doall
   [(create-table! users-spec)
    (create-table! messages-spec)
    (create-table! wordlgame-spec)
    (create-table! wordlewords-spec)
    (create-table! chessgames-spec)]))
  

(defn init-words! [_]
  (reduce (fn [day word]
            (sql/insert! db_games :wordlewords {:word word :day (str day)})
            (jt/plus day (jt/days 1)))
          (jt/local-date)
          (wordle-words)))


(comment
  (init-dbs! nil))

(comment
  (init-words! nil))

;; Utility
(defn client-id [req]
  (-> req
      :client-cert
      :sha256-hash))

(defn timestamp []
  (jt/format "YYYY-MM-dd HH:mm:ss" (jt/local-date-time)))

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

(def valid-words
  (delay
    (->> (sql/query db_games ["SELECT word FROM wordlewords"])
         (map :wordlewords/word)
         (into #{}))))

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
      (jdbc/execute! db_games ["UPDATE wordlegames SET score = score - 1 WHERE gameid = ?" game-id]))
    (sql/insert! db_games :wordlegames {:uid (client-id req)
                                        :guesses guess-str
                                        :score 6})))

(defn user-stats [req]
  (sql/query db_games ["SELECT * FROM wordlegames WHERE uid = ?" (client-id req)]))

(defn get-keyboard [req]
  (->>
   (sql/query db_games ["SELECT keyboard FROM wordlegames WHERE gameid = ?" (get-game-id req)])
   first
   :wordlegames/keyboard))

(defn update-keyboard! [req new-keyboard]
  (sql/update! db_games :wordlegames {:keyboard new-keyboard} {:gameid (get-game-id req)}))


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


(defn get-board-history [gameid]
  (-> (sql/query db_games ["SELECT boardstate FROM chessgames WHERE gameid = ?" gameid])
      first
      :chessgames/boardstate))

(defn valid-request? [req whiteID blackID playerturn]
  (case playerturn
    "white" (= (client-id req) whiteID)
    "black" (= (client-id req) blackID)
    nil))
  

;; TODO set enddate on win
(defn conclude-game [gameid colour ID]
  (sql/update! db_games :chessgames
               {:complete 1
                :winner colour
                :winnerID ID
                :enddate (timestamp)}
               {:gameid gameid}))

(defn update-chess-game [req {:keys [board-packed check checkmate notation player-input gameid] :as move}]
  (let [{:chessgames/keys [playerturn
                           whiteID
                           blackID
                           gamemoves
                           turncount
                           playerinput]} (first (get-gameinfo gameid))
        next-player                      (if (= playerturn "white")
                                           "black"
                                           "white")
        new-move-record                  (str gamemoves (when gamemoves ",") notation)
        new-player-input                 (str player-input (when playerinput ",") player-input)]
    (do
      (sql/update! db_games :chessgames {:boardstate  board-packed
                                         :playerturn  next-player
                                         :gamemoves   new-move-record
                                         :playerinput new-player-input
                                         :turncount   (inc turncount)
                                         :checkstate  (if check 1 0)}
                   {:gameid gameid})
      (when (= checkmate :checkmate)
        (let [winner-id     (if (= playerturn "white") whiteID blackID)]
          (conclude-game gameid playerturn winner-id))))))

(defn resign-game [req gameid]
  (let [{:chessgames/keys [playerturn whiteID blackID]} (first (get-gameinfo gameid))
        winner-colour (if (= playerturn "white") "black" "white")
        winner-id (if (= playerturn "white") whiteID blackID)]
    (when (valid-request? req whiteID blackID playerturn)
      (do
        (sql/update! db_games :chessgames {:resignstatus 1} {:gameid gameid})
        (conclude-game gameid winner-colour winner-id)))))

(defn draw-offered [req gameid]
  (let [{:chessgames/keys [playerturn whiteID blackID]} (first (get-gameinfo gameid))]
    (when (valid-request? req whiteID blackID playerturn)
      (sql/update! db_games :chessgames {:drawstatus 1} {:gameid gameid}))))

(defn draw-accepted [req gameid]
  (let [{:chessgames/keys [playerturn whiteID blackID]} (first (get-gameinfo gameid))]
    (when (valid-request? req whiteID blackID (if (= playerturn "white") "black" "white"))
      (do
        (sql/update! db_games :chessgames {:drawstatus 2} {:gameid gameid})
        (conclude-game gameid "tie-game" "tie-game")))))

(defn draw-rejected [req gameid]
  (let [{:chessgames/keys [playerturn whiteID blackID]} (first (get-gameinfo gameid))]
    (when (valid-request? req whiteID blackID (if (= playerturn "white") "black" "white"))
      (sql/update! db_games :chessgames {:drawstatus 0} {:gameid gameid}))))

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
      :else (println "Something went wrong: get-player-type"))))

(defn get-user-completed-games [req]
  (let [uid (client-id req)]
    (sql/query db_games ["SELECT * FROM chessgames WHERE (whiteID = ? OR blackID = ?) AND complete = 1" uid uid])))
