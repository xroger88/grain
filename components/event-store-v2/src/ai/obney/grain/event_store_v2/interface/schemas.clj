(ns ai.obney.grain.event-store-v2.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(defn- as-of-or-after [x] (not (and (:as-of x) (:after x))))

(defn- uuid-v7? [x] (and (uuid? x) (= 7 (uuid/get-version x))))

(defschemas api
  {::entity-type :keyword
   ::entity-id :uuid
   ::tag [:tuple ::entity-type ::entity-id]
   ::tags [:set ::tag]
   ::type :keyword
   ::types [:set ::type]
   ::uuid-v7 [:fn
              {:error/message "Must be UUID v7"}
              uuid-v7?]
   ::id ::uuid-v7
   ::timestamp [:time/offset-date-time]

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

   ::cas
   [:and
    ::as-of-or-after
    [:map
     [:tags  {:optional true} ::tags]
     [:types {:optional true} ::types]
     [:as-of {:optional true} ::id]
     [:after {:optional true} ::id]
     [:predicate-fn fn?]]]

   ::append-args
   [:map
    [:events [:vector ::event]]
    [:tx-metadata {:optional true} [:map]]
    [:cas {:optional true} ::cas]]

   ::->event-args
   [:map
    [:type ::type]
    [:tags {:optional true} ::tags]
    [:body {:optional true} [:map]]]

   :grain/tx
   [:map
    [:event-ids [:set ::id]]
    [:metadata {:optional true} [:map]]]})

(comment

  (require '[ai.obney.grain.time.interface :as t]
           '[malli.core :as mc])


  (mc/explain
   ::read-args
   {:tags #{[:user (random-uuid)]
            [:course (random-uuid)]}
    :types #{:user-enrolled
             :course-created
             :course-name-changed}})

  (mc/explain
   ::append-args
   {:events [{:event/id (uuid/v7)
              :event/timestamp (t/now)
              :event/tags #{}
              :event/type :user-name-changed
              :first-name "Cameron"
              :last-name "Barre"}]
    :tx-metadata {:hello "world"}
    :cas {:predicate-fn (fn [events] (empty? events))
          :tags #{[:user (uuid/v7)]}
          :types #{:user-name-changed}}})
  
  (mc/explain ::entity-type "tset")

  


  ""
  )