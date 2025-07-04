(ns ai.obney.grain.behavior-tree.interface
  (:require [ai.obney.grain.behavior-tree.core.nodes :as core]
            [ai.obney.grain.behavior-tree.core.blackboard :as blackboard]
            [ai.obney.grain.behavior-tree.core.builder :as builder]))

;; Re-export multi-methods for user convenience
(def execute-action builder/execute-action)
(def evaluate-condition builder/evaluate-condition)

(def success core/success)
(def failure core/failure)
(def running core/running)

(defn create-blackboard
  "Create a blackboard backed by event-store-v2"
  ([event-store]
   (blackboard/create-event-store-blackboard event-store))
  ([event-store blackboard-id]
   (blackboard/create-event-store-blackboard event-store blackboard-id)))


(defn build-behavior-tree
  "Build a complete behavior tree from configuration"
  [config]
  (builder/build-behavior-tree config))

(defn create-context
  "Create a context for behavior tree execution"
  [blackboard & {:keys [data] :or {data {}}}]
  (builder/create-context blackboard :data data))

(defn run-tree
  "Run a behavior tree with the given context"
  [tree context]
  (builder/run-tree tree context))

(defn get-value
  "Get a value from a blackboard"
  [blackboard key]
  (core/get-value blackboard key))

(defn set-value
  "Set a value in a blackboard"
  [blackboard key value]
  (core/set-value blackboard key value))

(defn remove-value
  "Remove a value from a blackboard"
  [blackboard key]
  (core/remove-value blackboard key))

(defn get-all
  "Get all values from a blackboard"
  [blackboard]
  (core/get-all blackboard))
