(ns ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
  (:require [com.brunobonacci.mulog.buffer :as mb]
            [clojure.data.json :as json]))

(defn- build-emf-event
  "Constructs an EMF JSON string from a μ/log event."
  [{metric-name :metric/name
    metric-value :metric/value
    duration-ns :mulog/duration
    timestamp :mulog/timestamp
    resolution :metric/resolution
    :keys [app-name env]
    :as _event}]
  (let [unit (cond
               (and metric-name metric-value) "Count"
               (and duration-ns metric-name) "Milliseconds"
               :else "None")
        value (or metric-value (/ duration-ns 1e6))]
    (json/write-str
     {:_aws {:Timestamp timestamp
             :CloudWatchMetrics
             [{:Namespace "ObneyAI/InfoSystem"
               :Dimensions [["app-name" "env"]]
               :Metrics [{:Name metric-name
                          :Unit unit
                          :StorageResolution
                          (case resolution
                            :high 1
                            :low 60
                            1)}]}]}
      :app-name app-name
      :env env
      (keyword metric-name) value})))

(deftype CloudWatchEMFPublisher [buffer]
  com.brunobonacci.mulog.publisher.PPublisher
  (agent-buffer [_] buffer)
  (publish-delay [_] 200)
  (publish [_ buffer]
    (doseq [event (map second (mb/items buffer))]
      (when (or (and (:metric/name event) (:metric/value event))
                (and (:metric/name event) (:mulog/duration event)))
        (println (build-emf-event event))))
    (mb/clear buffer)))

(defn cloudwatch-emf-publisher
  "Creates an instance of the CloudWatch EMF Publisher."
  [_config]
  (CloudWatchEMFPublisher. (mb/agent-buffer 10000)))