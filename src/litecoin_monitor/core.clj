(ns litecoin-monitor.core
  (:require [clj-http.client :as client]
            [cheshire.core :refer [parse-string]]
            [clojure.java.io :as io]
            [timely.core :refer :all])
  (:gen-class))

(defn load-config [filename]
  (with-open [r (io/reader filename)]
    (read (java.io.PushbackReader. r))))

(def config (load-config "miner.properties"))
(def endpoints (:miners config))
(def eth-endpoints (:ethdistro config))

(defn get-pool-data [src]
  (->
    src
    (client/get {:accept :json})
    (:body)
    (parse-string)))

(defn get-param [pool-data path]
  (->
    pool-data
    (get-in path)))

(defn get-hashrate [pool-data]
  (get-param pool-data ["user" "hash_rate"]))

(defn get-expected-reward [pool-data]
  (get-param pool-data ["user" "expected_24h_rewards"]))

(defn get-past-reward [pool-data]
  (get-param pool-data ["user" "past_24h_rewards"]))


(defn hashrate-in-bound [real expected treshold]
  (let [lower-bound (* treshold expected)]
    (>= real lower-bound)))

(defn map-endpoints [endpoints]
  (map
    #(let [data (get-pool-data (:src %1))]
      (merge %1 {:rate (get-hashrate data)
                 :expected-reward (get-expected-reward data)
                 :past-reward (get-past-reward data)}))
    endpoints))

(defn map-eth-endpoints [endpoints]
  (map
    #(let [data (get-pool-data (:src %1))
           rig (:rig %1)]
       (merge %1 {:rate (get-param data ["rigs" rig "hash"])}))
    endpoints))

(defn monitor [endpoints]
  (filter
    #(not (hashrate-in-bound
            (:rate %1)
            (:expected %1)
            (:treshold-ltc config)))
    (map-endpoints endpoints)))

(defn eth-monitor [endpoints]
  (filter
    #(not (hashrate-in-bound
            (:rate %1)
            (:expected %1)
            (:treshold-eth config)))
    (map-eth-endpoints endpoints)))

(defn format-hashrate [rate dividor]
  (str (quot rate dividor) "MH/s"))

(defn create-report-message [endpoints]
  (clojure.string/join
    "\n"
    (map
      #(str
        "Miner: *"
        (:name %1)
        "* got reward for past 24h _"
        (format "%.5f" (:past-reward %1))
        "_ and is expected _"
        (format "%.5f" (:expected-reward %1))
        "_")
      endpoints)))

(defn create-notify-messages [endpoints]
  (clojure.string/join
    "\n"
    (map
      #(str
          "Miner: *"
          (:name %1)
          "* has expected rate _"
          (format-hashrate (:expected %1) 1000)
          "_ but signaling _"
          (format-hashrate (:rate %1) 1000)
          "_")
      endpoints)))

(defn create-eth-notify-messages [endpoints]
  (clojure.string/join
    "\n"
    (map
      #(str
          "GPU Miner: *"
          (:name %1)
          "* has expected rate _"
          (format-hashrate (:expected %1) 1)
          "_ but signaling _"
          (format-hashrate (:rate %1) 1)
          "_")
      endpoints)))

(defn submit-when-message [msg]
  (when
    (not-empty msg)
    (client/post
      (:slack-url config)
      {:form-params {:text msg}
       :content-type :json})))

(defn set-interval [callback ms]
  (callback)
  (future (while true (do (Thread/sleep ms) (callback)))))

(defn create-job [job every-ms]
  (set-interval job every-ms))

(defn run-watcher []
  (->
    endpoints
    (monitor)
    (create-notify-messages)
    (submit-when-message)))

(defn run-eth-watcher []
  (->
    eth-endpoints
    (eth-monitor)
    (create-eth-notify-messages)
    (submit-when-message)))

(defn run-reporter []
  (->
    endpoints
    (map-endpoints)
    (create-report-message)
    (submit-when-message)))

(defn -main
  "Start scheduler and run watchers with reporters."
  [& args]
  (start-scheduler)
  (run-watcher)
  (run-eth-watcher)
  (let [sched-list (list
                     (scheduled-item (every (:every-min config) :minutes) #(run-watcher))
                     (scheduled-item (every (:every-min config) :minutes) #(run-eth-watcher))
                     (scheduled-item
                      (daily
                       (at (hour (:run-hour config)) (minute (:run-minutes config))))
                      #(run-reporter)))
        sched-ids (map #(start-schedule %1) sched-list)]
    (println sched-ids)
    (while true
      (Thread/sleep (* 1000 60)))))
