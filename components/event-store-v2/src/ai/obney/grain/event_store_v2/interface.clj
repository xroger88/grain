(ns ai.obney.grain.event-store-v2.interface
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v2.interface.schemas]
            [ai.obney.grain.event-store-v2.core :as core]))

(defn ->event
  [{:keys [_type _body _tags] :as args}]
  (core/->event args))

(defn start
  [config]
  (core/start config))

(defn stop
  [event-store]
  (core/stop event-store))

(defn append
  [event-store args]
  (core/append event-store args))

(defn read
  [event-store args]
  (core/read event-store args))




