(ns ai.obney.grain.webserver.interface
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.webserver.core :as core]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas schemas
  {::routes :set
   ::port :int
   ::join? :boolean
   ::start-args [:map
                 [:http/routes ::routes]
                 [:http/port ::port]
                 [:http/join? ::join?]]})

(defn start 
  [config]
  (core/start config))

(defn stop
  [webserver]
  (core/stop webserver))