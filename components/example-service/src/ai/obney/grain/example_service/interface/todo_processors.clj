(ns ai.obney.grain.example-service.interface.todo-processors
  (:require [ai.obney.grain.example-service.core.todo-processors :as core]))

(defn calculate-average-counter-value
  [context]
  (core/calculate-average-counter-value context))