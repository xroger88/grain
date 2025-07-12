(ns ai.obney.grain.behavior-tree.core.blackboard
  (:require [ai.obney.grain.behavior-tree.interface.protocols :as proto :refer [Blackboard]]
            [ai.obney.grain.event-store-v2.interface :as event-store-v2]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(def blackboard-entity-type :blackboard)

;; Define schemas for blackboard events
(defschemas blackboard-events
  {:blackboard/value-set
   [:map
    [:key :any]
    [:value :any]]
   
   :blackboard/value-removed
   [:map
    [:key :any]]
   
   :blackboard/state-initialized
   [:map
    [:initial-state [:map-of :keyword :any]]]})

(defn- apply-events-to-snapshot
  "Apply events to a snapshot to get the current state"
  [snapshot events]
  (reduce (fn [acc event]
            (case (:event/type event)
              :blackboard/value-set
              (assoc acc (:key event) (:value event))
              :blackboard/value-removed
              (dissoc acc (:key event))
              :blackboard/state-initialized
              (merge acc (:initial-state event)) 
              acc))
          snapshot
          events))

(defn- update-snapshot!
  "Update the snapshot with events newer than last-event-id"
  [blackboard]
  (let [event-store (:event-store blackboard)
        blackboard-id (:blackboard-id blackboard)
        cache-atom (:cache-atom blackboard)
        read-model-fn (:read-model-fn blackboard)
        domain-event-config (:domain-event-config blackboard)]
    (swap! cache-atom
           (fn [current-cache]
             (let [current-snapshot (:snapshot current-cache)
                   current-last-event-id (:last-event-id current-cache)]
               (if (nil? current-snapshot)
                 ;; No snapshot exists, build from scratch
                 (let [;; Get blackboard events (always filtered by blackboard-id)
                       bb-events (into
                                  []
                                  (event-store-v2/read
                                   event-store
                                   {:tags #{[blackboard-entity-type blackboard-id]}
                                    :types #{:blackboard/state-initialized 
                                             :blackboard/value-set 
                                             :blackboard/value-removed}}))
                       
                       ;; Get domain events using configured queries
                       domain-events (if domain-event-config
                                       (mapcat (fn [query]
                                                 (into [] (event-store-v2/read event-store query)))
                                               domain-event-config)
                                       [])
                       
                       ;; Build initial state from read model if provided
                       initial-state (if read-model-fn
                                       (read-model-fn domain-events)
                                       {})
                       
                       ;; Apply blackboard events on top of read model state
                       new-snapshot (apply-events-to-snapshot initial-state bb-events)
                       
                       ;; Track the latest event ID from ALL events for incremental updates
                       all-events (concat domain-events bb-events)
                       new-last-event-id (when (seq all-events) 
                                           (:event/id (last (sort-by :event/id all-events))))]
                   {:snapshot new-snapshot :last-event-id new-last-event-id})
                 ;; Snapshot exists, get only new events since last update
                 (let [;; Get new blackboard events
                       new-bb-events (into
                                      []
                                      (event-store-v2/read
                                       event-store
                                       {:tags #{[blackboard-entity-type blackboard-id]}
                                        :types #{:blackboard/state-initialized 
                                                 :blackboard/value-set 
                                                 :blackboard/value-removed}
                                        :after current-last-event-id}))
                       
                       ;; Get new domain events using configured queries
                       new-domain-events (if domain-event-config
                                           (mapcat (fn [query]
                                                     (let [query-with-after (assoc query :after current-last-event-id)]
                                                       (into [] (event-store-v2/read event-store query-with-after))))
                                                   domain-event-config)
                                           [])
                       
                       ;; Rebuild state if new domain events, otherwise just apply blackboard events
                       updated-snapshot (if (and read-model-fn (seq new-domain-events))
                                          ;; New domain events: need to rebuild domain state completely
                                          (let [;; Get ALL domain events to rebuild state
                                                all-domain-events (if domain-event-config
                                                                    (mapcat (fn [query]
                                                                              (into [] (event-store-v2/read event-store query)))
                                                                            domain-event-config)
                                                                    [])
                                                ;; Apply read model to get current domain state
                                                domain-state (read-model-fn all-domain-events)
                                                ;; Get ALL blackboard events and apply
                                                all-bb-events (into
                                                               []
                                                               (event-store-v2/read
                                                                event-store
                                                                {:tags #{[blackboard-entity-type blackboard-id]}
                                                                 :types #{:blackboard/value-set :blackboard/value-removed}}))
                                                final-state (apply-events-to-snapshot domain-state all-bb-events)]
                                            final-state)
                                          ;; No new domain events: just apply new blackboard events
                                          (apply-events-to-snapshot current-snapshot new-bb-events))
                       
                       ;; Track latest event ID from all new events
                       all-new-events (concat new-domain-events new-bb-events)
                       new-last-event-id (if (seq all-new-events)
                                           (:event/id (last (sort-by :event/id all-new-events)))
                                           current-last-event-id)]
                   {:snapshot updated-snapshot :last-event-id new-last-event-id})))))
    (:snapshot @cache-atom)))

(defrecord EventStoreBlackboard [event-store blackboard-id cache-atom read-model-fn domain-event-config]
  Blackboard
  (get-value [this key]
    (let [current-snapshot (update-snapshot! this)]
      (get current-snapshot key)))
  
  (set-value [this key value]
    (let [event-store (:event-store this)
          blackboard-id (:blackboard-id this)
          cache-atom (:cache-atom this)
          event (event-store-v2/->event
                 {:type :blackboard/value-set
                  :tags #{[blackboard-entity-type blackboard-id]}
                  :body {:key key
                         :value value}})]
      (event-store-v2/append event-store {:events [event]})
      ;; Optimistically update the snapshot if it exists
      (swap! cache-atom update :snapshot #(if % (assoc % key value) %)))
    this)
  
  (remove-value [this key]
    (let [event-store (:event-store this)
          blackboard-id (:blackboard-id this)
          cache-atom (:cache-atom this)
          event (event-store-v2/->event
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
   (create-event-store-blackboard event-store blackboard-id nil nil))
  ([event-store blackboard-id read-model-fn domain-event-config]
   (->EventStoreBlackboard event-store blackboard-id (atom {:snapshot nil :last-event-id nil}) read-model-fn domain-event-config)))

(defn create-event-sourced-blackboard
  "Create a pure event-sourced blackboard with optional read model and domain event config"
  [event-store & {:keys [blackboard-id read-model-fn domain-event-config initial-blackboard]}]
  (let [bb-id (or blackboard-id (uuid/v7))
        blackboard (create-event-store-blackboard 
                     event-store 
                     bb-id 
                     read-model-fn 
                     domain-event-config)]
    ;; If initial-blackboard is provided, create and append initialization event
    (when initial-blackboard
      (let [init-event (event-store-v2/->event
                        {:type :blackboard/state-initialized
                         :tags #{[blackboard-entity-type bb-id]}
                         :body {:initial-state initial-blackboard}})]
        (event-store-v2/append event-store {:events [init-event]})))
    blackboard))