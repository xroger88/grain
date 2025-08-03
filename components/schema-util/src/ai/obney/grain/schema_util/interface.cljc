(ns ai.obney.grain.schema-util.interface
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.experimental.time :as mt]
            #?@(:clj [[clojure.core.async.impl.protocols :refer [Channel]]])))

(def registry* (atom (merge (mt/schemas) (m/default-schemas))))

(defn register!
  [schema-map]
  (swap! registry* merge schema-map))

#?(:clj (defmacro defschemas
          [symbol schema-map]
          `(do
             (#'register! ~schema-map)
             (def ~symbol ~schema-map))))

(mr/set-default-registry!
 (mr/mutable-registry registry*))

;;
;; Default Schemas
;;

#?(:clj (defschemas schemas
          {::channel [:fn #(satisfies? Channel %)]})
   :cljs (register! {}))


