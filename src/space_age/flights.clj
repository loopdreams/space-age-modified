(ns space-age.flights
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

;;;;;;;;;;;;
;; Carbon ;;
;;;;;;;;;;;;


;; Avg 12 liters per KM
(def avg-fuel-usage-per-km 12)

(def avg-fuel-usage-takeoff-landing 1100)

(def avg-no-passengers 300)

;; Source - https://www.statista.com/statistics/986460/co2-emissions-per-cap-eu/#:~:text=Greenhouse%20gas%20emissions%20per%20capita%20in%20the%20European%20Union%201990%2D2021&text=Per%20capita%20greenhouse%20gas%20(GHG,tons%20of%20carbon%20dioxide%20equivalent.
(def avg-person-annual-emissions 7.77e3)

;; Source - https://www.umweltbundesamt.de/daten/klima/treibhausgas-emissionen-in-der-europaeischen-union#hauptverursacher
(def recommended-annual-avg 0.6e3)

(defn- total-co2-emitted
  "The '3.16' is the 'emissions factor'"
  [distance]
  (*
   (+ (* distance avg-fuel-usage-per-km) ;; this should prob be adjusted for shorted distance after takeoff/landing
      avg-fuel-usage-takeoff-landing)
   3.16))

(defn- personal-co2-contribution [total-c02]
  (/ total-c02 avg-no-passengers))


(defn personal-co2-emissions [distance]
  (-> distance
      total-co2-emitted
      personal-co2-contribution))


;;;;;;;;;;;;;;;;;;;
;;;; Fuzzy ;;;;;;;;
;;;;;;;;;;;;;;;;;;;


;; Functions taken from - https://github.com/Yomguithereal/clj-fuzzy

(defn n-grams
  "Lazily compute the n-grams of a sequence."
  [n s]
  (partition n 1 s))

(defn- letter-sets
  [n string]
  (set (n-grams n (-> (str/replace string #"\s+" "")
                      (str/upper-case)))))
;; Main functions
(defn coefficient
  "Compute the Dice coefficient between two [strings]."
  [string1 string2 & {:keys [n] :or {n 2}}]
  (cond (= string1 string2) 1.0
        (and (< (count string1) 2)
             (< (count string2) 2)) 0.0
        :else (let [p1 (letter-sets n string1)
                    p2 (letter-sets n string2)
                    sum (+ (count p1) (count p2))]
                (/ (* 2.0 (count (set/intersection p1 p2)))
                   sum))))

;;;;;;;;;;;;;;;;
;;;;  DB  ;;;;;;
;;;;;;;;;;;;;;;;

(def db "data/global_airports_sqlite.db")

(def db_flights (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/global_airports_sqlite.db"}))

(defn- make-query-space [db]
  (reduce (fn [q-space entry]
            (let [{:airports/keys [name city country]} entry]
              (when name
                (assoc q-space
                       (str/join " " [name city country]) ;; presuming each query is unique
                       {:name name
                        :city city
                        :country country}))))

          {}
          (sql/query db_flights ["SELECT name, city, country FROM airports"])))


(defn- suggest-match [query candidates]
  (let [[best-match cooef]
        (->>
         (reduce (fn [results candidate]
                   (assoc results candidate
                          (coefficient query candidate)))
                 {}
                 candidates)
         (sort-by val)
         (reverse)
         first)]
    (when (pos? cooef)
      best-match)))

(defn snake->kebab
  "Very loose function, used becase of known snake-case values in source data."
  [key]
  (let [name (name key)]
    (->
     (str/replace name "_" "-")
     keyword)))

(defn transform-keys-to-kebab-case
  "For the sake of consistency in api outputs."
  [m]
  (let [ks (map snake->kebab (keys m))]
    (zipmap ks (vals m))))


(defn get-data-by-query [db query]
  (let [lookup              (make-query-space db)
        match               (->> (keys lookup)
                                 (remove #(re-find #"N/A" %)) ;; Filtering out :name with "N/A", since these appear to have 0,0 as lat/lng
                                 (suggest-match query))
        {:keys [name city country]} (lookup match)]
    (-> (sql/query db ["SELECT name, city, country, lat_decimal, lon_decimal FROM airports WHERE name = ? AND city = ? AND country = ?" name city country])
        first
        transform-keys-to-kebab-case)))


(defn airports-by-city-name [db city-query]
  (let [candidates (map :airports/city (sql/query db ["SELECT city FROM airports"]))
        city       (suggest-match city-query candidates)]
    {:city city
     :airports
     (->> (sql/query db ["SELECT name FROM airports WHERE city = ? AND NOT name  = 'N/A'" city])
          (mapv (comp str/capitalize :airports/name)))}))


(defn cities-by-country-name [db country-query]
  (let [candidates (map :airports/country (sql/query db ["SELECT country FROM airports"]))
        country    (suggest-match country-query candidates)]
    {:country country
     :cities
     (->> (sql/query db ["SELECT city FROM airports WHERE country = ? AND NOT name = 'N/A'" country])
          (mapv (comp str/capitalize :airports/city)))}))

(def query-find (partial get-data-by-query db_flights))

(def query-airports (partial airports-by-city-name db_flights))

(def query-cities (partial cities-by-country-name db_flights))


;;;;;;;;;;;;;;;;;;;;;;
;;;; Distance ;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;

;; Calculating Distance between lat and lon

;; Earth's redius - 6,371km
(def R 6371e3)

(def rad-v (/ Math/PI 180))
;; Using this as a guide - https://www.movable-type.co.uk/scripts/latlong.html
(defn calculate-distance [lat1 lon1 lat2 lon2]
  (let [l1 (* lat1 rad-v)
        l2 (* lat2 rad-v)
        dlat (* (- lat2 lat1) rad-v)
        dlon (* (- lon2 lon1) rad-v)
        a (+ (Math/pow (Math/sin (/ dlat 2)) 2)
             (* (Math/cos l1)
                (Math/cos l2)
                (Math/pow (Math/sin (/ dlon 2)) 2)))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* R c)))

(defn distance [location1 location2]
  (let [lat1 (:lat-decimal location1)
        lon1 (:lon-decimal location1)
        lat2 (:lat-decimal location2)
        lon2 (:lon-decimal location2)]
    (calculate-distance lat1 lon1 lat2 lon2)))


;; Average Air speed - 880–926 km/h
;; Average Air speed - 885-965 km/h
;; But, some distance is covered in take-off and landing and would need
;; to be factored in here ...
;; So, randomly reduced a little
(def air-speed [740 850])

(def take-off-and-landing-time 0.5)

(defn generate-flight-data [loc1 loc2]
  (let [distance         (-> (distance loc1 loc2)
                             (/ 1000)
                             float)
        co2-em           (personal-co2-emissions distance)
        diff-recommended (- co2-em recommended-annual-avg)]
    {:origin                     loc1
     :destination                loc2
     :co2-personal               (int co2-em)
     :co2-percentage-annual-avg  (int (* 100 (/ co2-em avg-person-annual-emissions)))
     :co2-difference-recommended diff-recommended
     :distance                   distance
     :flight-time                (map #(+ (/ distance %) take-off-and-landing-time)
                                      (reverse air-speed))}))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;   Message        ;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Formatting for the main printed outputs

(defn- hours->hr-min [hours]
  (let [h (int hours)
        mins  (int (* (mod hours 1) 60))]
    (str h (if (= 1 h) " hour, " " hours, ")
         mins (if (= 1 mins) " minute" " minutes"))))

(defn- airport-location-str [{:keys [name city country]}]
  (str (str/capitalize name)
       " Airport in "
       (str/capitalize city)
       ", "
       (str/capitalize country)))

(defn flight-time-msg [{:keys [flight-time]}]
  (let [[lwr upr] (map hours->hr-min flight-time)]
    (str "Estimated Flight Time: Between " lwr
         " and "
         upr)))

(defn oirgin-and-destination-msg [{:keys [origin destination]}]
  (str/join "\n"
            [(str "Origin: " (airport-location-str origin))
             (str "Destination: " (airport-location-str destination))]))

(defn city-names-msg [{:keys [country cities]}]
  (str "The following cities have airports in " country
       ":\n- "
       (str/join "\n- " cities)))

(defn airport-names-msg [{:keys [city airports]}]
  (str "The following airports are in " city
       ":\n- "
       (str/join "\n- " airports)))


(def bar-symbol (char 9632))

(defn bar-string [percentage]
  (let [len (* 50 (/ percentage 100))]
    (str "[" (str/join (repeat len bar-symbol)) "]")))

(def co2-annual-avg-bar (str (bar-string 100)
                             " EU Person Annual Avg: "
                             (int avg-person-annual-emissions)
                             " kg"))

(defn personal-co2-usage-bar [co2-personal co2-percentage]
  (str
   (bar-string co2-percentage)
   " Carbon cost: " co2-personal " kg / "
   co2-percentage "% of annual EU avg"))


(defn recommended-co2-bar []
  (str
   (bar-string (* 100 (/ recommended-annual-avg avg-person-annual-emissions)))
   " Recommended annual avg: " recommended-annual-avg
   " kg"))

(defn carbon-msg [{:keys [co2-personal co2-percentage-annual-avg]}]
  (str/join "\n"
            [(personal-co2-usage-bar co2-personal co2-percentage-annual-avg)
             (recommended-co2-bar)
             co2-annual-avg-bar]))


;;;;;;;;;;;;;;;;;;;;;;
;;;;; Main   ;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;

(defn flight-data-message [{:keys [distance] :as data}]
  (let [loc-msg      (oirgin-and-destination-msg data)
        distance-msg (str "Approximate Distance: " (int distance) " km")
        time-msg     (flight-time-msg data)
        carbon-msg   (carbon-msg data)]
    (str "-------------------------------------------------------------------\n"
         (str/join "\n\n"
                   [loc-msg
                    distance-msg
                    time-msg
                    carbon-msg])
         "\n-------------------------------------------------------------------")))

(defn cities-data [query]
  (query-cities query))

(defn airports-data [query]
  (query-airports query))

(defn flight-data [queries]
  (let [[q1 q2] queries
        [loc1 loc2] (map query-find queries)]
    (if (and loc1 loc2)
      (generate-flight-data loc1 loc2)
      (println (str "Not Found: " (if loc1 q2 q1))))))

(defn flight-message [queries]
  (flight-data-message (flight-data queries)))
