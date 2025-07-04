(ns ai.obney.grain.behavior-tree.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :as schema-util]))

(schema-util/defschemas events
  {:blackboard/value-set
   [:map
    [:event/name [:= :blackboard/value-set]]
    [:event/id :uuid]
    [:event/timestamp inst?]
    [:event/tags [:set [:tuple keyword? :uuid]]]
    [:key :keyword]
    [:value :any]]
   
   :blackboard/value-removed
   [:map
    [:event/name [:= :blackboard/value-removed]]
    [:event/id :uuid]
    [:event/timestamp inst?]
    [:event/tags [:set [:tuple keyword? :uuid]]]
    [:key :keyword]]})

(schema-util/defschemas behavior-tree-config
  {:behavior-tree/sequence
   [:and
    :vector
    [:fn (fn [v] (= (first v) :sequence))]
    [:fn (fn [v] (every? vector? (rest v)))]]
   
   :behavior-tree/fallback
   [:and
    :vector
    [:fn (fn [v] (= (first v) :fallback))]
    [:fn (fn [v] (every? vector? (rest v)))]]
   
   :behavior-tree/parallel
   [:and
    :vector
    [:fn (fn [v] (= (first v) :parallel))]
    [:fn (fn [v] (every? vector? (rest v)))]]
   
   :behavior-tree/condition
   [:and
    :vector
    [:fn (fn [v] (= (first v) :condition))]
    [:fn (fn [v] (keyword? (second v)))]]
   
   :behavior-tree/action
   [:and
    :vector
    [:fn (fn [v] (= (first v) :action))]
    [:fn (fn [v] (keyword? (second v)))]]
   
   :behavior-tree/inverter
   [:and
    :vector
    [:fn (fn [v] (= (first v) :inverter))]
    [:fn (fn [v] (vector? (second v)))]]
   
   :behavior-tree/retry
   [:and
    :vector
    [:fn (fn [v] (= (first v) :retry))]
    [:fn (fn [v] (vector? (second v)))]
    [:fn (fn [v] (or (nil? (nth v 2 nil)) (int? (nth v 2 nil))))]]
   
   :behavior-tree/always-succeed
   [:and
    :vector
    [:fn (fn [v] (= (first v) :always-succeed))]
    [:fn (fn [v] (vector? (second v)))]]
   
   :behavior-tree/always-fail
   [:and
    :vector
    [:fn (fn [v] (= (first v) :always-fail))]
    [:fn (fn [v] (vector? (second v)))]]
   
   :behavior-tree/node
   [:or
    :behavior-tree/sequence
    :behavior-tree/fallback
    :behavior-tree/parallel
    :behavior-tree/condition
    :behavior-tree/action
    :behavior-tree/inverter
    :behavior-tree/retry
    :behavior-tree/always-succeed
    :behavior-tree/always-fail]})