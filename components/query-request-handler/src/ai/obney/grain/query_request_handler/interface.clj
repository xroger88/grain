(ns ai.obney.grain.query-request-handler.interface
  (:require [ai.obney.grain.query-request-handler.core :as core]))

(defn routes
  [config]
  (core/routes config))

(defn handle-query
  [config query]
  (core/handle-query config query))