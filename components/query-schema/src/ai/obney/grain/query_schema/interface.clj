(ns ai.obney.grain.query-schema.interface
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))


#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas schemas
  {::query-id :uuid
   ::query-name :qualified-keyword
   ::query-timestamp :time/offset-date-time
   ::query [:map
            [:query/name ::query-name]
            [:query/id ::query-id]
            [:query/timestamp ::query-timestamp]]})
