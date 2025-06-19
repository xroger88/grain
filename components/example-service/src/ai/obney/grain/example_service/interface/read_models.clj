(ns ai.obney.grain.example-service.interface.read-models
  (:require [ai.obney.grain.example-service.core.read-models :as core]))

(defn root
  [context]
  (core/root context))