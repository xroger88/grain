(ns ai.obney.grain.core-async-thread-pool.core
  (:require [clojure.core.async :as async]))

(defn start
  [{:keys [thread-count execution-fn error-fn in-chan]}]

  (dotimes [_ thread-count]
    (async/go-loop []
      (when-let [job (async/<! in-chan)]
        (try
          (execution-fn job)
          (catch Exception e
            (error-fn e)))
        (recur))))

  {:stop-fn (fn []
              (async/close! in-chan))})

(defn stop
  [{:keys [stop-fn]}]
  (stop-fn))
