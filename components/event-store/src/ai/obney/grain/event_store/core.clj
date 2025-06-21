(ns ai.obney.grain.event-store.core
  (:require [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.event-store.interface.schemas :as event-schema]
            [ai.obney.grain.event-store.interface.protocols :as p :refer [start-event-store]]
            [ai.obney.grain.event-store.core.in-memory]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as mc]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))

(defmethod start-event-store :default
  [{{:keys [type]} :conn}]
  (throw (ex-info (format "Unsupported event store type: %s" type) {:type type})))

(defn start
  [config]
  (assoc-in (start-event-store config)
            [:config :event-pubsub]
            (:event-pubsub config)))

(defn stop
  [event-store]
  (p/stop event-store))

(defn store-events
  [{{:keys [event-pubsub]} :config
    :as event-store}
   {:keys [events] :as args}]
  (let [validation-errors
        (try (->> events
                  (mapv #(mc/explain [:and ::event-schema/event (:event/name %)] %))
                  (filterv (complement nil?)))
             (catch clojure.lang.ExceptionInfo _
               {::anom/category ::anom/fault
                ::anom/message "One or more event schemas are not defined for :event/name"
                ::event-count (count events)
                ::event-names (map :event/name events)}))]
    (cond
      (anomaly? validation-errors)
      validation-errors

      (seq validation-errors)
      (do
        (u/log ::validation-errors :validation-errors validation-errors)
        {::anom/category ::anom/fault
         ::anom/message "Invalid Event(s): Failed Schema Validation"
         :error/explain validation-errors})

      :else
      (let [result (p/store-events event-store args)]
        (if (anomaly? result)
          result
          (when event-pubsub
            (run! #(pubsub/pub event-pubsub {:message %}) events)))))))

(defn get-events
  [event-store args]
  (p/get-events event-store args))

(defn current-entity-version
  [event-store entity-id]
  (p/current-entity-version event-store entity-id))

(defn event
  [{:keys [name entity-id body]}]
  (merge
   {:event/id (uuid/v7)
    :event/timestamp (time/now)
    :event/name name
    :event/entity-id entity-id}
   body))