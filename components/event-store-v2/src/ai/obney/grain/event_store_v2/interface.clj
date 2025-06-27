(ns ai.obney.grain.event-store-v2.interface
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]
            [malli.core :as mc]))

(defn- as-of-or-after [x] (not (and (:as-of x) (:after x))))

(defschemas api
  {::entity-type :keyword
   ::entity-id :uuid
   ::tag [:tuple ::entity-type ::entity-id]
   ::tags [:set ::tag]
   ::type :keyword
   ::types [:set ::type]
   ::uuid-v7 [:fn #(and (uuid? %) (= 7 (uuid/get-version %)))]
   ::id ::uuid-v7
   ::timestamp :time/offset-date-time

   ::event [:map
            [:event/id ::id]
            [:event/timestamp ::timestamp]
            [:event/tags ::tags]
            [:event/type ::type]]

   ::as-of-or-after
   [:fn {:error/message "Cannot supply both :as-of and :after"} as-of-or-after]

   ::read-args
   [:and
    ::as-of-or-after
    [:map
     [:tags  {:optional true} ::tags]
     [:types {:optional true} ::types]
     [:as-of {:optional true}  ::id]
     [:after {:optional true} ::id]]]
   
   ::append-args
   [:map
    [:events [:vector ::event]]
    [:cas {:optional true} 
     [:map
      [:tags {:optional true} ::tags]
      [:types {:optional true} ::types]
      ]]]})

(defprotocol EventStore
  (start [this args])
  (stop [this args])
  (append [this args])
  (read [this args]
    "Read a lazy sequence of events from the event store.
     
     If no tags or types are provided, a lazy sequence of all events is returned.

     Cannot supply both :as-of and :after at the same time.

     May return a cognitect anomaly.
     
     args:
     
     A map with the following optional keys:
     
     :tags - A set of tags to filter events by. Each tag is a tuple of entity type and entity ID.
     
     :types - A set of event types to filter events by.
     
     :as-of - A UUID v7 event id to filter events that occurred before or at this time.
     
     :after - A UUID v7 event id to filter events that occurred after this time."))

(comment

  

  (mc/explain
   ::read-args
   {:tags #{[:user (random-uuid)]
            [:course (random-uuid)]}
    :types #{:user-enrolled
             :course-created
             :course-name-changed}})

  ""
  )