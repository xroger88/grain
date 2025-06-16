(ns ai.obney.grain.time.interface)

(defn now [] (java.time.OffsetDateTime/now))

(defn now-from-str [s] (java.time.OffsetDateTime/parse s))

(defn now-from-ms [ms]
  (java.time.OffsetDateTime/ofInstant
   (java.time.Instant/ofEpochMilli ms)
   java.time.ZoneOffset/UTC))