(ns litecoin-monitor.core
  (:require [clj-http.client :as client]
            [cheshire.core :refer [parse-string]]
            [clojure.java.io :as io])
  (:gen-class))

(defn load-config [filename]
  (with-open [r (io/reader filename)]
    (read (java.io.PushbackReader. r))))

(def config (load-config "miner.properties"))
(def endpoints (:miners config))

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


(defn hashrate-in-bound [real expected]
  (let [lower-bound (* (:treshold config) expected)]
    (>= real lower-bound)))

(defn map-endpoints [endpoints]
  (map
    #(let [data (get-pool-data (:src %1))]
      (merge %1 {:rate (get-hashrate data)
                 :expected-reward (get-expected-reward data)
                 :past-reward (get-past-reward data)}))
    endpoints))

(defn monitor [endpoints]
  (filter
    #(not (hashrate-in-bound (:rate %1) (:expected %1)))
    (map-endpoints endpoints)))

(defn format-hashrate [rate]
  (str (quot rate 1000) "MH/s"))

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
          (format-hashrate (:expected %1))
          "_ but signaling _"
          (format-hashrate (:rate %1))
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

(defn start-watcher []
  (create-job
    #(->
      endpoints
      (monitor)
      (create-notify-messages)
      (submit-when-message))
    (* (:ever-min config) 60 1000)))

(defn start-reporter []
  (create-job
    #(->
      endpoints
      (map-endpoints)
      (create-report-message)
      (submit-when-message))
    (* 24 60 60 1000)))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (start-watcher)
  (start-reporter))
