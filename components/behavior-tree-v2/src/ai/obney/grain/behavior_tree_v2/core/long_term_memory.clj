(ns ai.obney.grain.behavior-tree-v2.core.long-term-memory
  (:require [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]
            [ai.obney.grain.event-store-v2.interface :as es]))

(defn latest
  "Return the latest long-term memory read model as a map.
   
   

   args: 
    - config:
      - event-store: The event store to read from.
      - read-model-fn: Function of signature [initial-state events] to build the read model from events.
      - queries: Vector of queries to run against the event store.
   - state: Atom containing:
     - latest-event-id: Optional event ID to start reading from (for incremental updates).
     - snapshot: Initial state atom to use for the read model.
   "
  [{{:keys [event-store read-model-fn queries]} :config 
    :keys [state] :as _this}]
  (when-not (and event-store read-model-fn queries)
    (throw (ex-info "Long Term Memory not configured" {})))
  (let [{:keys [snapshot latest-event-id]} @state
        events (mapcat
                (fn [query]
                  (into [] (es/read
                            event-store
                            (cond-> query
                              latest-event-id (assoc :after latest-event-id)))))
                queries)
        read-model (read-model-fn (or snapshot {}) events)]
    (swap! state assoc 
           :snapshot read-model 
           :latest-event-id (if events (->> events last :event/id) latest-event-id))
    read-model))

(defrecord LongTermMemoryEventStore [config]
  p/LongTermMemory
  (latest [this]
    (latest this)))

(defn ->long-term-memory
  [config]
  (assoc (->LongTermMemoryEventStore config)
         :state (atom {})))

(comment

  (require '[ai.obney.grain.schema-util.interface :refer [defschemas]])

  (defschemas events
    {:counter-created
     [:map
      [:counter-id :uuid]]

     :counter-incremented
     [:map
      [:counter-id :uuid]]

     :counter-decremented
     [:map
      [:counter-id :uuid]]})

  (def event-store
    (es/start {:conn {:type :in-memory}}))

  (def counter-id (random-uuid))

  (es/append
   event-store
   {:events
    [(es/->event
      {:type :counter-created
       :tags #{[:counter counter-id]}
       :body {:counter-id counter-id}})

     (es/->event
      {:type :counter-incremented
       :tags #{[:counter counter-id]}
       :body {:counter-id counter-id}})

     (es/->event
      {:type :counter-incremented
       :tags #{[:counter counter-id]}
       :body {:counter-id counter-id}})

     (es/->event
      {:type :counter-decremented
       :tags #{[:counter counter-id]}
       :body {:counter-id counter-id}})]})


  (def ltm
    (->long-term-memory
     {:event-store event-store
      :read-model-fn
      (fn [initial-state events]
        (reduce (fn [state event]
                  (case (:event/type event)
                    :counter-created (assoc state
                                            :counter-id (:counter-id event)
                                            :count 0)
                    :counter-incremented (update state :count inc)
                    :counter-decremented (update state :count dec)
                    :grain/tx (update state :tx/log conj event)
                    state))
                initial-state
                events))
      :queries
      [{:types #{:counter-created
                 :counter-incremented
                 :counter-decremented}
        :tags #{[:counter counter-id]}}
       {:types #{:grain/tx}}]}))
  
  (p/latest ltm)








  ""
  )