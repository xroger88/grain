(ns ai.obney.grain.event-store.interface
  (:require [ai.obney.grain.event-store.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop
  [event-store]
  (core/stop event-store))

(defn store-events
  [event-store args]
  (core/store-events event-store args))

(defn get-events
  [event-store args]
  (core/get-events event-store args))

(defn current-entity-version 
  [event-store entity-id]
  (core/current-entity-version event-store entity-id))

(defn event
  [{:keys [_name _entity-id _body] :as args}]
  (core/event args))