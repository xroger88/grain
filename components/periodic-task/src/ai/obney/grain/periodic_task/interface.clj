(ns ai.obney.grain.periodic-task.interface
  (:require [ai.obney.grain.periodic-task.core :as core]))

(defn start [config]
  (core/start config))

(defn stop [periodic-task]
  (core/stop periodic-task))

