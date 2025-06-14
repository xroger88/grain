(ns ai.obney.grain.event-store.interface.protocols)

(defmulti start-event-store #(get-in % [:conn :type]))

(defprotocol EventStore
  (start [this])
  (stop [this])
  (store-events [this args])
  (get-events [this args])
  (current-entity-version [this entity-id]))