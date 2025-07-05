(ns ai.obney.grain.behavior-tree.core.blackboard
  (:require [ai.obney.grain.behavior-tree.interface.protocols :as proto :refer [Blackboard]]
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

(defn- apply-events-to-snapshot
  "Apply events to a snapshot to get the current state"
  [snapshot events]
  (reduce (fn [acc event]
            (case (:event/type event)
              :blackboard/value-set
              (assoc acc (:key event) (:value event))
              :blackboard/value-removed
              (dissoc acc (:key event))
              acc))
          snapshot
          events))

(defn- update-snapshot!
  "Update the snapshot with events newer than last-event-id"
  [blackboard]
  (let [event-store (:event-store blackboard)
        blackboard-id (:blackboard-id blackboard)
        cache-atom (:cache-atom blackboard)]
    (swap! cache-atom
           (fn [current-cache]
             (let [current-snapshot (:snapshot current-cache)
                   current-last-event-id (:last-event-id current-cache)]
               (if (nil? current-snapshot)
                 ;; No snapshot exists, build from scratch
                 (let [events (into
                               []
                               (event-store-v2/read
                                event-store
                                {:tags #{[blackboard-entity-type blackboard-id]}
                                 :types #{:blackboard/value-set :blackboard/value-removed}}))
                       new-snapshot (apply-events-to-snapshot {} events)
                       new-last-event-id (when (seq events) (:event/id (last events)))]
                   {:snapshot new-snapshot :last-event-id new-last-event-id})
                 ;; Snapshot exists, get only new events
                 (let [events (into
                               []
                               (event-store-v2/read
                                event-store
                                {:tags #{[blackboard-entity-type blackboard-id]}
                                 :types #{:blackboard/value-set :blackboard/value-removed}
                                 :after current-last-event-id}))
                       new-snapshot (apply-events-to-snapshot current-snapshot events)
                       new-last-event-id (if (seq events)
                                           (:event/id (last events))
                                           current-last-event-id)]
                   {:snapshot new-snapshot :last-event-id new-last-event-id})))))
    (:snapshot @cache-atom)))

(defrecord EventStoreBlackboard [event-store blackboard-id cache-atom]
  Blackboard
  (get-value [this key]
    (let [current-snapshot (update-snapshot! this)]
      (get current-snapshot key)))
  
  (set-value [this key value]
    (let [event (event-store-v2/->event
                 {:type :blackboard/value-set
                  :tags #{[blackboard-entity-type blackboard-id]}
                  :body {:key key
                         :value value}})]
      (event-store-v2/append event-store {:events [event]})
      ;; Optimistically update the snapshot if it exists
      (swap! cache-atom update :snapshot #(if % (assoc % key value) %)))
    this)
  
  (remove-value [this key]
    (let [event (event-store-v2/->event
                 {:type :blackboard/value-removed
                  :tags #{[blackboard-entity-type blackboard-id]}
                  :body {:key key}})]
      (event-store-v2/append event-store {:events [event]})
      ;; Optimistically update the snapshot if it exists
      (swap! cache-atom update :snapshot #(if % (dissoc % key) %)))
    this)
  
  (get-all [this]
    (update-snapshot! this)))

(defn create-event-store-blackboard
  "Create a blackboard backed by event-store-v2"
  ([event-store]
   (create-event-store-blackboard event-store (uuid/v7)))
  ([event-store blackboard-id]
   (->EventStoreBlackboard event-store blackboard-id (atom {:snapshot nil :last-event-id nil}))))