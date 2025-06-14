(ns ai.obney.grain.event-store.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas schemas
  {::event-id :uuid
   ::event-name :qualified-keyword
   ::event-timestamp :time/offset-date-time
   ::entity-id :uuid
   ::event [:map
            [:event/id ::event-id]
            [:event/name ::event-name]
            [:event/timestamp ::event-timestamp]
            [:event/entity-id ::entity-id]]})