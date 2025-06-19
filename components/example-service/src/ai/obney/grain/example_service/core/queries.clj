(ns ai.obney.grain.example-service.core.queries
  "The core queries namespace in a grain service component implements
     the query handlers and defines the query registry. Query functions
     take a context that includes any necessary dependencies, to be injected
     in the base for the service. Usually a query-request-handler or another 
     type of adapter will call the query processor, which will access the query 
     registry for the entire application in the context. Queries either return a cognitect 
     anomaly or a map that optionally has a :query/result which is some 
     data that is meant to be returned to the caller, see query-request-handler for example."
  (:require [ai.obney.grain.example-service.interface.read-models :as read-models]
            [cognitect.anomalies :as anom]))

(defn counters 
  [context]
  (let [counters (->> (read-models/root context)
                      vals)]
    {:query/result counters}))

(defn counter
  [{{:keys [counter-id]} :query :as context}]
  (let [counter (-> (read-models/root context)
                    (get counter-id))]
    (if counter
      {:query/result counter}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))

(def queries
  {:example/counters {:handler-fn #'counters}
   :example/counter {:handler-fn #'counter}})