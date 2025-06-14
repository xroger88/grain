(ns ai.obney.grain.todo-processor.interface
  (:require [ai.obney.grain.todo-processor.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop 
  [todo-processor]
  (core/stop todo-processor))