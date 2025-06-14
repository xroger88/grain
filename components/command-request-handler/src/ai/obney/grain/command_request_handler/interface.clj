(ns ai.obney.grain.command-request-handler.interface
  (:require [ai.obney.grain.command-request-handler.core :as core]))

(defn routes
  [config]
  (core/routes config))

(defn handle-command
  [config command]
  (core/handle-command config command))