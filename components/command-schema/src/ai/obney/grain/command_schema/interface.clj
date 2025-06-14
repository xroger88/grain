(ns ai.obney.grain.command-schema.interface
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas schemas
  {::command-id :uuid
   ::command-name :qualified-keyword
   ::command-timestamp :time/offset-date-time
   ::command [:map
              [:command/name ::command-name]
              [:command/id ::command-id]
              [:command/timestamp ::command-timestamp]]})
