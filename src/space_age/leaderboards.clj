(ns space-age.leaderboards
  (:require [space-age.db :as db]
            [next.jdbc.sql :as sql]))

(defn wordle-leaderboard-data []
  (let [games (sql/query db/db_games ["SELECT uid, SUM(score) AS score FROM wordlegames WHERE win = 1 GROUP BY uid ORDER BY score DESC LIMIT 10"])
        uid->name (fn [{:wordlegames/keys [uid] :as data}]
                    (assoc data :name (db/get-username-by-id uid)))]
    (map uid->name games)))

(defn wordle-daily-leaderboard-data []
  (let [games (sql/query db/db_games ["SELECT uid, score FROM wordlegames WHERE win = 1 AND strftime('%Y-%m-%d', gamedate) = DATE('now') ORDER BY score DESC"])
        uid->name (fn [{:wordlegames/keys [uid score] :as data}]
                    (-> data
                        (assoc :name (db/get-username-by-id uid))
                        (assoc :score score)))]
    (map uid->name games)))

(defn chess-leaderboard-data []
  (let [games        (sql/query db/db_games ["SELECT winnerID FROM chessgames WHERE complete = 1 AND NOT winnerID = 'tie-game'"])
        tie-games    (sql/query db/db_games ["SELECT whiteID, blackID FROM chessgames WHERE complete = 1 AND winnerID = 'tie-game'"])
        win-scores   (-> (map :chessgames/winnerID games)
                         frequencies)
        win-scores   (reduce (fn [scores-doubled entry]
                               (assoc scores-doubled entry (* 2 (win-scores entry))))
                             {}
                             (keys win-scores))
        tie-scores   (-> (reduce #(into %1 (vals %2)) [] tie-games)
                         frequencies)
        total-scores (merge-with + win-scores tie-scores)]
    (->>
     (reduce (fn [result id]
               (conj result
                     (-> {}
                         (assoc :name (db/get-username-by-id id))
                         (assoc :score (total-scores id)))))
             []
             (keys total-scores))
     (sort-by :score)
     reverse
     (take 10))))
