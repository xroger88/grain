(ns ai.obney.grain.behavior-tree-v2.interface.protocol)

(def success :success)
(def failure :failure)
(def running :running)

(defmulti tick
  "Execute the node and return success, failure, or running."
  (fn [node _context] (:type node)))

(defmulti build
  "Build a behavior tree node based on its type."
  (fn [type _args] type))

(defmulti condition
  (fn [condition-key _context] condition-key))

(defmulti action
  (fn [action-key _context] action-key))

(defn opts+children
  "Extract options and children from the config vector."
  [args]
  (if (and (seq args) (map? (first args)))
    [(first args) (rest args)]
    [{} args]))

(defprotocol LongTermMemory
  "Protocol for providing long-term memory access."
  (latest [this]
    "Return the latest long-term memory read model as a map."))