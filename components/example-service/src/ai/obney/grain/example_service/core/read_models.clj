(ns ai.obney.grain.example-service.core.read-models
  "The core read-models namespace in a grain app is where projections are created from events.
   Events are retrieved using the event-store and the read model is built through reducing usually.
   These tend to be used by the other components of the grain app, such as commands, queries, periodic tasks, 
   and todo-processors."
  (:require [ai.obney.grain.event-store-v2.interface :as event-store]
            [com.brunobonacci.mulog :as u]))

(defmulti apply-event
  "Apply an event to the read model."
  (fn [_state event]
    (:event/type event)))

(defmethod apply-event :example/counter-created
  [state {:keys [counter-id name]}]
  (assoc state counter-id
         {:counter/id counter-id
          :counter/name name}))

(defmethod apply-event :example/counter-incremented
  [state {:keys [counter-id]}]
  (update state counter-id update :counter/value (fnil inc 0)))

(defmethod apply-event :example/counter-decremented
  [state {:keys [counter-id]}]
  (update state counter-id update :counter/value (fnil dec 0)))

(defmethod apply-event :default
  [state _event]
  ;; If the event is not recognized, return the state unchanged.
  state)

(defn apply-events
  "Applies a sequence of events to the read model state."
  [events]
  (let [result (when (seq events)
                 (reduce
                  (fn [state event]
                    (apply-event state event))
                  {}
                  events))]
    (when (seq result)
      result)))

(defn root
  "Returns the root entity of the read model."
  [{:keys [event-store] :as _context}]
  (let [events (event-store/read
                event-store
                {:types #{:example/counter-created
                          :example/counter-incremented
                          :example/counter-decremented}})
        state (u/trace
               ::read-model-root
               [:metric/name "ReadModelExampleRoot"]
               (apply-events events))]
    state))