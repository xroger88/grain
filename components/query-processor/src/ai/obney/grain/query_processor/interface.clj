(ns ai.obney.grain.query-processor.interface
  (:require [ai.obney.grain.query-processor.core :as core]))

(defn process-query [context]
  (core/process-query context))