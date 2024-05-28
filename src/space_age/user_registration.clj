(ns space-age.user-registration
  (:require [space-age.db :as db]
            [clojure.string :as str]))

;; For user registration and shared functions (user stats)
(def break "\n\n")

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

;; User Stats

;; Wordle

;; Also set in wordle script as 'guess limit'
(def wordle-guess-limit 6)

(def bar-symbol (char 9632))
;; (def bar-symbol (char 9608))

(defn bar-string [percentage count]
  (let [len (* 20 (/ percentage 100))]
    (str "[" (str/join (repeat len bar-symbol)) "] " count)))

(defn stats-bars [win-frequencies]
  (let [[[_ full]] win-frequencies]
    (for [i (range 1 (inc wordle-guess-limit))
          :let [[_ len] (or (first (filter #(= (first %) i) win-frequencies))
                            [i 0])
                percentage (* 100 (/ len full))]]
      (bar-string percentage len))))


(defn wordle-stats [req]
  (let [stats       (db/user-stats req)
        total-games (count stats)]

    (if-not (> total-games 0)
      (str "You haven't played any games yet.")

      (let [wins      (filter #(= (:wordlegames/win %) 1) stats)]
        (if-not (> (count wins) 0)
          (str "Total games played: " total-games "\n"
               "You haven't won any games yet, keep trying!\n")

          (let [win-count (count wins)
                win-rate (int (* 100 (/ win-count total-games)))
                scores (->> (map :wordlegames/score wins)
                            frequencies
                            (sort-by second)
                            reverse
                            stats-bars
                            (str/join "\n"))]
            (str "Total games played: " total-games "\n"
                 "Win rate: " (or win-rate "") "%\n"
                 "```\n"
                 "---------------------\n"
                 (or scores "")
                 "\n---------------------"
                 "\n```")))))))

;; Chess
(defn chess-stats
  "TODO Include other things like average amount of moves taken, etc."
  [uid game-info]
  (let [number-games (count game-info)
        win-count (->> (map :chessgames/winnerID game-info)
                       (filter #(= % uid))
                       count)]
    (str "You have played " number-games " games. You have won " win-count " games so far.")))


(defn chess-history [req]
  (let [uid (db/client-id req)
        completed-games (db/get-user-completed-games req)
        stats (chess-stats uid completed-games)]
    (str stats
         break
         (str/join "\n"
                   (for [game completed-games
                         :let [{:chessgames/keys [gameid whiteID blackID]} game
                               white (db/get-username-by-id whiteID)
                               black (db/get-username-by-id blackID)]]
                     (str "=> /src/app/chess/game/" gameid " Game " gameid " between " white " and " black))))))
