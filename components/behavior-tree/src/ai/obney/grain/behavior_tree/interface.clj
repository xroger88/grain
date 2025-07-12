(ns ai.obney.grain.behavior-tree.interface
  (:require [ai.obney.grain.behavior-tree.interface.protocols :as proto]
            [ai.obney.grain.behavior-tree.core.blackboard :as blackboard]
            [ai.obney.grain.behavior-tree.core.builder :as builder]))

(defn execute
  "Execute a behavior tree with event-store backed blackboard.
  
  Required:
  - event-store: The event store instance
  - tree-config: Vector-based behavior tree configuration
  
  Optional (via options map):
  - :blackboard-id - Custom blackboard ID (defaults to random UUID)
  - :read-model-fn - Function (events) -> map that builds state from events in the event store
                     Events have structure: {:event/id, :event/timestamp, :event/type, :event/tags, ...body-fields}
  - :domain-event-config - Vector of event-store query maps for domain events the agent should track
                           Each query specifies :types and :tags for efficient filtering
                           Example: [{:types #{:document/processed} :tags #{[:document-type :research]}}
                                    {:types #{:analysis/completed} :tags #{[:project project-id]}}]
  - :context-data - Additional data to merge into execution context
  - :initial-blackboard - Map of key-value pairs to initialize blackboard state
                          Creates a single :blackboard/state-initialized event
  
  NOTE: All blackboard state is derived purely from events in the event store.
        Use :initial-blackboard for convenient initialization, or write events manually.
  
  Returns a map with :result (:success, :failure, or :running) and :blackboard"
  [event-store tree-config & {:keys [blackboard-id read-model-fn domain-event-config context-data initial-blackboard]
                               :or {context-data {}}}]
  (let [;; Create pure event-sourced blackboard with optional initial state
        blackboard (blackboard/create-event-sourced-blackboard
                     event-store
                     :blackboard-id blackboard-id
                     :read-model-fn read-model-fn
                     :domain-event-config domain-event-config
                     :initial-blackboard initial-blackboard)
        
        ;; Build tree and create context  
        tree (builder/build-behavior-tree tree-config)
        context (builder/create-context blackboard :data context-data)
        
        ;; Execute the tree
        result (builder/run-tree tree context)]
    
    {:result result
     :blackboard blackboard}))

;; Low-level blackboard access (use sparingly - prefer execute functions)
(defn get-value
  "Get a value from a blackboard"
  [blackboard key]
  (proto/get-value blackboard key))

(defn set-value
  "Set a value in a blackboard"
  [blackboard key value]
  (proto/set-value blackboard key value))

(defn get-all
  "Get all values from a blackboard"
  [blackboard]
  (proto/get-all blackboard))
