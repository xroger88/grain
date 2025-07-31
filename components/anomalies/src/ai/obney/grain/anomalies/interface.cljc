(ns ai.obney.grain.anomalies.interface
  (:require [cognitect.anomalies :as anom]))

(defn anomaly? [x] (when (::anom/category x) x))