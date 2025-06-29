(ns ai.obney.grain.example-service.core.commands
  "The core commands namespace in a grain service component implements
   the command handlers and defines the command registry. Command functions
   take a context that includes any necessary dependencies, to be injected
   in the base for the service. Usually a command-request-handler or another 
   type of adapter will call the command processor, which will access the command 
   registry for the entire application in the context. Commands either return a cognitect 
   anomaly or a map that optionally has a :command-result/events key containing a sequence of 
   valid events per the event-store event schema and optionally :command/result which is some 
   data that is meant to be returned to the caller, see command-request-handler for example."
  (:require [ai.obney.grain.example-service.interface.read-models :as read-models]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [cognitect.anomalies :as anom]))

(defn create-counter
  "Creates a new counter. Counter name must be unique."
  [context]
  (let [counter-name (get-in context [:command :name])
        counter-id (random-uuid)
        unique-counter-names (->> (read-models/root context)
                                  vals
                                  (map :counter/name)
                                  set)]
    (if (contains? unique-counter-names counter-name)
      {::anom/category ::anom/conflict
       ::anom/message (format "Counter with name '%s' already exists." counter-name)}
      {:command-result/events
       [(->event {:type :example/counter-created
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id
                         :name counter-name}})]})))

(defn increment-counter
  "Increments an existing counter by 1."
  [{{:keys [counter-id]} :command :as context}]
  (let [state (read-models/root context)]
    (if (get state counter-id)
      {:command-result/events
       [(->event {:type :example/counter-incremented
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id}})]}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))

(defn decrement-counter
  "Decrements an existing counter by 1."
  [{{:keys [counter-id]} :command :as context}]
  (let [state (read-models/root context)]
    (if (get state counter-id)
      {:command-result/events
       [(->event {:type :example/counter-decremented
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id}})]}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))

(defn calculate-average-counter-value
  "Calculates the average value of all counters."
  [context]
  (let [state (read-models/root context)]
    {:command-result/events
     [(->event
       {:type :example/average-calculated
        :body {:value (/ (double (->> state
                                      vals
                                      (map :counter/value)
                                      (filter identity)
                                      (reduce + 0)))
                         (double (count state)))}})]}))

(def commands
  {:example/create-counter {:handler-fn #'create-counter}
   :example/increment-counter {:handler-fn #'increment-counter}
   :example/decrement-counter {:handler-fn #'decrement-counter}
   :example/calculate-average-counter-value {:handler-fn #'calculate-average-counter-value}})