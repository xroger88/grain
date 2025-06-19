(ns ai.obney.grain.example-service.interface.periodic-tasks
  (:require [ai.obney.grain.example-service.core.periodic-tasks :as core]))

(defn example-periodic-task
  [context time]
  (core/example-periodic-task context time))