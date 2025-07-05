(ns ai.obney.grain.behavior-tree.core.nodes
  (:require [ai.obney.grain.behavior-tree.interface.protocols :as proto :refer [BehaviorNode Blackboard success failure running tick get-value set-value remove-value]]))


(defrecord SequenceNode [children]
  BehaviorNode
  (tick [this context]
    (loop [children children]
      (if (empty? children)
        success
        (let [result (tick (first children) context)]
          (case result
            :success (recur (rest children))
            :failure failure
            :running running))))))

(defrecord FallbackNode [children]
  BehaviorNode
  (tick [this context]
    (loop [children children]
      (if (empty? children)
        failure
        (let [result (tick (first children) context)]
          (case result
            :success success
            :failure (recur (rest children))
            :running running))))))

(defrecord ParallelNode [children success-threshold]
  BehaviorNode
  (tick [this context]
    ;; Execute all children in parallel using futures
    (let [futures (mapv #(future (tick % context)) children)
          ;; Wait for completion and collect results
          results (mapv deref futures)
          success-count (count (filter #(= % success) results))
          failure-count (count (filter #(= % failure) results))
          running-count (count (filter #(= % running) results))]
      (cond
        (>= success-count success-threshold) success
        (> failure-count (- (count children) success-threshold)) failure
        :else running))))

(defrecord ConditionNode [condition-fn]
  BehaviorNode
  (tick [this context]
    (if (condition-fn context)
      success
      failure)))

(defrecord ActionNode [action-fn]
  BehaviorNode
  (tick [this context]
    (action-fn context)))

(defrecord DecoratorNode [child decorator-fn]
  BehaviorNode
  (tick [this context]
    (decorator-fn child context)))

(defn sequence-node
  "Create a sequence node that succeeds only if all children succeed"
  [& children]
  (->SequenceNode (vec children)))

(defn fallback-node
  "Create a fallback node that succeeds if any child succeeds"
  [& children]
  (->FallbackNode (vec children)))

(defn parallel-node
  "Create a parallel node that runs all children simultaneously"
  ([children]
   (parallel-node children 1))
  ([children success-threshold]
   (->ParallelNode (vec children) success-threshold)))

(defn condition-node
  "Create a condition node that evaluates a predicate"
  [condition-fn]
  (->ConditionNode condition-fn))

(defn action-node
  "Create an action node that executes a function"
  [action-fn]
  (->ActionNode action-fn))

(defn decorator-node
  "Create a decorator node that modifies child behavior"
  [child decorator-fn]
  (->DecoratorNode child decorator-fn))

(defn inverter-node
  "Create an inverter decorator that flips success/failure"
  [child]
  (decorator-node child
    (fn [child context]
      (case (tick child context)
        :success failure
        :failure success
        :running running))))

(defn retry-node
  "Create a retry decorator that retries a child on failure"
  [child max-retries]
  (decorator-node child
    (fn [child context]
      (let [blackboard (:blackboard context)
            retry-key (str (hash child) "-retries")
            current-retries (or (get-value blackboard retry-key) 0)]
        (case (tick child context)
          :success (do
                     (remove-value blackboard retry-key)
                     success)
          :failure (if (< current-retries max-retries)
                     (do
                       (set-value blackboard retry-key (inc current-retries))
                       failure)
                     (do
                       (remove-value blackboard retry-key)
                       failure))
          :running running)))))

(defn always-succeed-node
  "Create a decorator that always returns success"
  [child]
  (decorator-node child
    (fn [child context]
      (tick child context)
      success)))

(defn always-fail-node
  "Create a decorator that always returns failure"
  [child]
  (decorator-node child
    (fn [child context]
      (tick child context)
      failure)))

(defn run-behavior-tree
  "Execute a behavior tree with the given context"
  [tree context]
  (tick tree context))