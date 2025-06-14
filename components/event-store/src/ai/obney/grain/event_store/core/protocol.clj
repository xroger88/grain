(ns ai.obney.grain.event-store.core.protocol)

(defprotocol EventStore
  (start [this])
  (stop [this])
  (store-events [this args])
  (get-events [this args])
  (current-entity-version [this entity-id]))