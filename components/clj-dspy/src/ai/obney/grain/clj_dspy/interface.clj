(ns ai.obney.grain.clj-dspy.interface
  (:require [ai.obney.grain.clj-dspy.core :as core]))

(defn initialize-python!
  "Initialize Python and import required modules. Safe to call multiple times."
  []
  (core/initialize-python!))

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

(defmacro defmodel
  "Define a Pydantic model with automatic namespacing.
  
  Example:
  (defmodel User
    {:id [:int {:desc \"Unique identifier for the user\"}]
     :name [:string {:desc \"Name of the user\"}]
     :email [:string {:desc \"Email address of the user\"}]})
  
  Creates a namespaced model accessible as both User and namespace.User"
  [name fields]
  `(core/defmodel ~name ~fields))

(defn validate
  "Validate data against a Pydantic model. Returns the validated model instance."
  [model data]
  (core/validate-with-model model data))

(defn inspect-python
  "Inspect the underlying Python class for signatures or models.
  
  Useful for debugging and understanding the generated Python objects.
  
  Example:
  (inspect-python MySignature)
  (inspect-python MyModel)"
  [python-obj-or-sig-def]
  (core/inspect-python-obj python-obj-or-sig-def))
