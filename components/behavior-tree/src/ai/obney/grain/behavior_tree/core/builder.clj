(ns ai.obney.grain.behavior-tree.core.builder
  (:require [ai.obney.grain.behavior-tree.core.nodes :as bt-core]
            [ai.obney.grain.behavior-tree.interface.protocols :as proto :refer [execute-action evaluate-condition]]))

(defn build-node
  "Build a behavior tree node from vector format: [:type {:opts} child1 child2 ...]"
  [config]
  (when-not (vector? config)
    (throw (ex-info "Config must be a vector" {:config config})))
  
  (let [node-type (first config)
        rest-args (rest config)
        ;; Check if second element is an options map
        [opts children] (if (and (seq rest-args) (map? (first rest-args)))
                          [(first rest-args) (rest rest-args)]
                          [{} rest-args])]
    (case node-type
      :sequence
      (apply bt-core/sequence-node 
        (mapv build-node children))
      
      :fallback
      (apply bt-core/fallback-node 
        (mapv build-node children))
      
      :parallel
      (bt-core/parallel-node 
        (mapv build-node children)
        (get opts :success-threshold 1))
      
      :condition
      (let [condition-key (first children)]
        (bt-core/condition-node 
          (fn [context] (evaluate-condition condition-key (merge context opts)))))
      
      :action
      (let [action-key (first children)]
        (bt-core/action-node 
          (fn [context] 
            (execute-action action-key (merge context opts)))))
      
      :inverter
      (bt-core/inverter-node 
        (build-node (first children)))
      
      :retry
      (let [child (first children)
            max-retries (get opts :max-retries 3)]
        (bt-core/retry-node 
          (build-node child)
          max-retries))
      
      :always-succeed
      (bt-core/always-succeed-node 
        (build-node (first children)))
      
      :always-fail
      (bt-core/always-fail-node 
        (build-node (first children)))
      
      (throw (ex-info "Unknown node type" {:type node-type})))))

(defn build-behavior-tree
  "Build a complete behavior tree from vector configuration"
  [config]
  (let [root-node (build-node config)]
    {:root root-node}))

(defn create-context
  "Create a context for behavior tree execution"
  [blackboard & {:keys [data] :or {data {}}}]
  (merge {:blackboard blackboard} data))

(defn run-tree
  "Run a behavior tree with the given context"
  [tree context]
  (bt-core/run-behavior-tree (:root tree) context))