(ns ai.obney.grain.schema-util.interface
  (:require [malli.experimental.time :as mt]
            [malli.core :as m]
            [malli.registry :as mr]
            [clojure.core.async.impl.protocols :refer [Channel]]))

(def ^:private registry* (atom (merge (mt/schemas) (m/default-schemas))))

(defn ^:private register!
  [schema-map]
  (swap! registry* merge schema-map))

(defmacro defschemas
  [symbol schema-map]
  `(do
     (#'register! ~schema-map)
     (def ~symbol ~schema-map)))

(mr/set-default-registry!
 (mr/mutable-registry registry*))

;;
;; Default Schemas
;;

(defschemas schemas
  {::channel [:fn #(satisfies? Channel %)]})
