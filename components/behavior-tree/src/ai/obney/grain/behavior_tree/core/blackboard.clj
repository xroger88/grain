(ns ai.obney.grain.behavior-tree.core.blackboard
  (:require [ai.obney.grain.behavior-tree.core.nodes :as bt-core]
            [ai.obney.grain.event-store-v2.interface :as event-store-v2]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(def blackboard-entity-type :blackboard)
(def blackboard-entity-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000000"))

;; Define schemas for blackboard events
(defschemas blackboard-events
  {:blackboard/value-set
   [:map
    [:key :any]
    [:value :any]]
   
   :blackboard/value-removed
   [:map
    [:key :any]]})

(defrecord EventStoreBlackboard [event-store blackboard-id]
  bt-core/Blackboard
  (get-value [this key]
    (let [events (event-store-v2/read event-store 
                   {:tags #{[blackboard-entity-type blackboard-id]}
                    :types #{:blackboard/value-set :blackboard/value-removed}})
          final-state (reduce (fn [acc event]
                                (case (:event/type event)
                                  :blackboard/value-set
                                  (if (= (:key event) key)
                                    (assoc acc :value (:value event))
                                    acc)
                                  :blackboard/value-removed
                                  (if (= (:key event) key)
                                    (dissoc acc :value)
                                    acc)
                                  acc))
                              {}
                              events)]
      (get final-state :value)))
  
  (set-value [this key value]
    (let [event (event-store-v2/->event
                 {:type :blackboard/value-set
                  :tags #{[blackboard-entity-type blackboard-id]}
                  :body {:key key
                         :value value}})]
      (event-store-v2/append event-store {:events [event]}))
    this)
  
  (remove-value [this key]
    (let [event (event-store-v2/->event
                 {:type :blackboard/value-removed
                  :tags #{[blackboard-entity-type blackboard-id]}
                  :body {:key key}})]
      (event-store-v2/append event-store {:events [event]}))
    this)
  
  (get-all [this]
    (let [events (event-store-v2/read event-store 
                   {:tags #{[blackboard-entity-type blackboard-id]}
                    :types #{:blackboard/value-set :blackboard/value-removed}})]
      (reduce (fn [acc event]
                (case (:event/type event)
                  :blackboard/value-set
                  (assoc acc (:key event) (:value event))
                  :blackboard/value-removed
                  (dissoc acc (:key event))
                  acc))
              {}
              events))))

(defn create-event-store-blackboard
  "Create a blackboard backed by event-store-v2"
  ([event-store]
   (create-event-store-blackboard event-store (uuid/v7)))
  ([event-store blackboard-id]
   (->EventStoreBlackboard event-store blackboard-id)))