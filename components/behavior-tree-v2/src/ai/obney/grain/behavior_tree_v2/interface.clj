(ns ai.obney.grain.behavior-tree-v2.interface
  (:require [ai.obney.grain.behavior-tree-v2.core.engine :as core]
            [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]))

(def success p/success)
(def failure p/failure)
(def running p/running)

(defn build
  [config context]
  (core/build config context))

(defn run 
  [bt]
  (core/run bt))
