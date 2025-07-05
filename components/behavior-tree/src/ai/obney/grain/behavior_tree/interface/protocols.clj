(ns ai.obney.grain.behavior-tree.interface.protocols
  "Protocols and multimethods for behavior tree components")

;; =============================================================================
;; CONSTANTS
;; =============================================================================

(def ^:const success :success)
(def ^:const failure :failure)
(def ^:const running :running)

;; =============================================================================
;; PROTOCOLS
;; =============================================================================

(defprotocol BehaviorNode
  "Protocol for behavior tree nodes"
  (tick [this context]
    "Execute the node and return success, failure, or running"))

(defprotocol Blackboard
  "Protocol for blackboard data store"
  (get-value [this key]
    "Get a value from the blackboard")
  (set-value [this key value]
    "Set a value in the blackboard")
  (remove-value [this key]
    "Remove a value from the blackboard")
  (get-all [this]
    "Get all values from the blackboard"))

;; =============================================================================
;; MULTIMETHODS
;; =============================================================================

(defmulti execute-action
  "Execute a behavior tree action. Dispatch on action keyword."
  (fn [action-key context] action-key))

(defmulti evaluate-condition
  "Evaluate a behavior tree condition. Dispatch on condition keyword."
  (fn [condition-key context] condition-key))

;; Default implementations
(defmethod execute-action :default [action-key context]
  (throw (ex-info "Unknown action" {:action-key action-key})))

(defmethod evaluate-condition :default [condition-key context]
  (throw (ex-info "Unknown condition" {:condition-key condition-key})))

;; =============================================================================
;; BUILT-IN ACTIONS
;; =============================================================================

(defmethod execute-action :succeed [_ _] success)
(defmethod execute-action :fail [_ _] failure)

(defmethod execute-action :log [_ context]
  (let [message (or (:message context) "No message")]
    (println "Action log:" message)
    success))

(defmethod execute-action :wait [_ context]
  (let [duration (or (:duration context) 1000)]
    (println (str "Waiting " duration "ms"))
    (Thread/sleep duration)
    success))

(defmethod execute-action :set-blackboard-value [_ context]
  (let [blackboard (:blackboard context)
        key (:key context)
        value (:value context)]
    (when (and blackboard key)
      (set-value blackboard key value))
    success))

;; =============================================================================
;; BUILT-IN CONDITIONS  
;; =============================================================================

(defmethod evaluate-condition :always-true [_ _] true)
(defmethod evaluate-condition :always-false [_ _] false)