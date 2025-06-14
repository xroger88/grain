(ns ai.obney.grain.time.interface)

(defn now [] (java.time.OffsetDateTime/now))

(defn now-from-str [s] (java.time.OffsetDateTime/parse s))