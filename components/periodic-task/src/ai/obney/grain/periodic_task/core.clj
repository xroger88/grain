(ns ai.obney.grain.periodic-task.core
  (:require [chime.core :as chime]
            [com.brunobonacci.mulog :as u])
  (:import [java.time Instant Duration]))

(defn start [{{:keys [every duration]} :schedule
              :keys [handler-fn _task-name _schedule] :as args}]
  (u/log ::starting-periodic-task ::args args)
  (let [now (Instant/now)
        schedule-seq (chime/periodic-seq
                      now
                      (case duration
                        :seconds (Duration/ofSeconds every)
                        :minutes (Duration/ofMinutes every)
                        :hours (Duration/ofHours every)))]
    {::task (chime/chime-at schedule-seq handler-fn)
     ::args args}))

(defn stop [{::keys [task args]}]
  (u/log ::stopping-periodic-task ::args args)
  (.close task))


(comment

  (def task
    (start
     {:schedule {:every 1 :duration :seconds}
      :handler-fn (fn [_time] (println "HELLO"))
      :task-name ::hello-world-task}))

  (stop task)

  ""
  )

