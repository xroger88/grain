(ns ai.obney.grain.example-service.core.todo-processors
  "The core todo-processors namespace in a grain service is where todo-processor handler functions are defined.
   These functions receive a context and have a specific return signature. They can return a cognitect anomaly,
   a map with a `:result/events` key containing a sequence of valid events per the event-store event 
   schema, or an empty map. Sometimes the todo-processor will just call a command through the commant-processor.
   The wiring up of the context and the function occurs in the grain app base. The todo-processor subscribes to 
   one or more events via pubsub and only ever processes a single event at a time, which is included in the context."
  (:require [ai.obney.grain.command-processor.interface :as command-processor]
            [ai.obney.grain.time.interface :as time]))

(defn calculate-average-counter-value
  "Calculates the average value of the counter from the given todo items."
  [{:keys [_event] :as context}]
  (command-processor/process-command
   (assoc context
          :command
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :example/calculate-average-counter-value})))
