(ns ai.obney.grain.core-async-thread-pool.interface
  (:require [ai.obney.grain.core-async-thread-pool.core :as core]
            [ai.obney.grain.schema-util.interface :as schemas :refer [defschemas]]))

(defschemas schemas
  {::thread-count :int
   ::execution-fn :function
   ::error-fn :function
   ::in-chan ::schemas/channel

   ::start-args [:map
                 [:thread-count ::thread-count]
                 [:execution-fn ::execution-fn]
                 [:error-fn ::error-fn]
                 [:in-chan ::in-chan]]

   
   ::stop-fn :function

   ::start-output [:map
                   [:stop-fn ::stop-fn]]})

(defn start [config]
  (core/start config))

(defn stop [thread-pool]
  (core/stop thread-pool))

