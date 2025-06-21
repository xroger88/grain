(ns ai.obney.grain.event-store.core.in-memory
  (:require [ai.obney.grain.event-store.interface.protocols :as p]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]))

;; Note: The protocol and multi-method should probably be defined in the event store interface
;; instead of core

(defn start
  [_config]
  (ref {:events []}))

(defn stop
  [state]
  (dosync (ref-set state nil)))

(defn get-events
  [event-store {:keys [entity-id selection ignore] :as args}]
  (u/trace
   ::get-events
   [::args args]
   (let [all-events (-> event-store :state deref :events)
         selection-set (when selection (set selection))
         ignore-set    (when ignore (set ignore))
         filtered-events
         (->> all-events
              (filter
               (fn [event]
                 (and
                  (or (not entity-id)
                      (= (:event/entity-id event) entity-id))
                  (or (not selection-set)
                      (contains? selection-set (:event/name event)))
                  (or (not ignore-set)
                      (not (contains? ignore-set (:event/name event))))))))]
     filtered-events)))

(defn store-events
  [event-store
   {{:keys [entity-id selection ignore read-model-fn pred-fn] :as cas} :cas
    :keys [events]}]
  (u/trace
   ::storing-events
   [::events events]
   (dosync
    (if cas
      (let [events* (get-events event-store
                                ;; NOTE: Should entity-id be required here or optional like in get-events?
                                (cond-> {}
                                  entity-id (assoc :entity-id entity-id)
                                  selection (assoc :selection selection)
                                  ignore (assoc :ignore ignore)))
            read-model (read-model-fn events*)
            pred-result (pred-fn read-model)]
        (if pred-result
          (alter (:state event-store) update :events into events)
          (let [anomaly {::anom/category ::anom/conflict
                         ::anom/message "CAS failed"
                         ::cas cas}]
            (u/log ::cas-failed :anomaly anomaly)
            anomaly)))
      (do
        (println events)
        (alter (:state event-store) update :events into events))))))

(defn current-entity-version
  [event-store entity-id]
  (u/trace
   ::current-entity-version
   [::entity-id entity-id]
   (let [events (get-events event-store {:entity-id entity-id})]
     (count events))))

(defrecord InMemoryEventStore [config]
  p/EventStore

  (start [this]
    (assoc this :state (start config)))

  (stop [this]
    (stop (:state this))
    (dissoc this :state))

  (store-events [this args]
    (store-events this args))

  (get-events [this args]
    (get-events this args))

  (current-entity-version
    [this entity-id]
    (current-entity-version this entity-id)))

(defmethod p/start-event-store :in-memory
  [config]
  (p/start (->InMemoryEventStore config)))