(ns litecoin-monitor.core
  (:require [clj-http.client :as client]
            [cheshire.core :refer [parse-string]]
            [clojure.java.io :as io]))

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

(defn get-hashrate [pool-data]
  (->
    pool-data
    (get-in ["user" "hash_rate"])))

(defn hashrate-in-bound [real expected]
  (let [lower-bound (* (:treshold config) expected)]
    (>= real lower-bound)))

(defn monitor [endpoints]
  (filter
    #(not (hashrate-in-bound (:rate %1) (:expected %1)))
    (map
      #(assoc %1 :rate (get-hashrate (get-pool-data (:src %1))))
      endpoints)))

(defn format-hashrate [rate]
  (str (quot rate 1000) "MH/s"))

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
      "https://hooks.slack.com/services/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"
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


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (start-watcher))
