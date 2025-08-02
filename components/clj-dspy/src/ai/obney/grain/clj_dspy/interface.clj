(ns ai.obney.grain.clj-dspy.interface
  (:require [ai.obney.grain.clj-dspy.core :as core]))

(defmacro defsignature
  "Define a DSPy signature with automatic schema validation and namespacing.
  
  Example:
  (defsignature QA
    \"Answer questions clearly and concisely\"
    {:inputs {:question [:string {:desc \"The question to answer\"}]}
     :outputs {:answer [:string {:desc \"The answer to the question\"}]}})
  
  Creates a namespaced signature accessible as both QA and namespace.QA"
  [name & args]
  `(core/defsignature ~name ~@args))
