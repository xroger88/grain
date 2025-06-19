(ns ai.obney.grain.example-service.core.todo-processors
  "The core todo-processors namespace in a grain service is where todo-processor handler functions are defined.
   These functions receive a context and have a specific return signature. They can either return a cognitect anomaly
   or they can return a map with a `:result/events` key containing a sequence of valid events per the event-store event 
   schema. The wiring up of the context and the function occurs in the grain app base. The todo-processor subscribes to 
   one or more events via pubsub and only ever processes a single event at a time, which is included in the context."
  (:require [ai.obney.grain.example-service.interface.read-models :as read-models]
            [ai.obney.grain.event-store.interface :refer [event]]
            [clj-uuid :as uuid]))

(defn calculate-average-counter-value
  "Calculates the average value of the counter from the given todo items."
  [{:keys [_event] :as context}]
  (let [state (read-models/root context)]
    {:result/events
     [(event
       {:name :example/average-calculated
        :entity-id uuid/+null+
        :body {:value (/ (double (->> state
                                      vals
                                      (map :counter/value)
                                      (reduce + 0)))
                         (double (count state)))}})]}))
