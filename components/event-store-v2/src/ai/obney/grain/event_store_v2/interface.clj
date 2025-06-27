(ns ai.obney.grain.event-store-v2.interface
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(defschemas api
  {::entity-type :qualified-keyword
   ::entity-id :uuid
   ::tag [:tuple ::entity-type ::entity-id]
   ::tags [:vector ::tag]
   ::type :qualified-keyword
   ::uuid-v7 [:fn #(and (uuid? %) (= 7 (uuid/get-version %)))]
   ::id ::uuid-v7
   ::timestamp :time/offset-date-time

   ::event [:map
            [:event/id ::id]
            [:event/timestamp ::timestamp]
            [:event/tags ::tags]
            [:event/type ::type]]})

(defprotocol EventStore
  (start [this args])
  (stop [this args])
  (append [this args])
  (read [this args]
    "Read events from the event store.
     
     Args:
     
     :tags - 
     
     :types - "))

(comment

  (uuid/get-version (uuid/v7))

  (uuid/uuid? (uuid/v7))

  (uuid? (uuid/v7))


  "")