(ns ai.obney.grain.command-processor.interface
  (:require [ai.obney.grain.command-processor.core :as core]))

(defn process-command [context]
  (core/process-command context))
