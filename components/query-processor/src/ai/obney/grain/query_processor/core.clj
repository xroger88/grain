(ns ai.obney.grain.query-processor.core
  (:require
   [com.brunobonacci.mulog :as u]
   [cognitect.anomalies :as anom]
   [malli.core :as mc]
   [malli.error :as me]))

(defn process-query [{:keys [query query-registry] :as context}]
  (u/trace
   ::process-query
   [::query query]
   (let [query-name (:query/name query)
         handler (get-in query-registry [query-name :handler-fn])]
     (if handler
       (if-let [_ (mc/validate query-name query)]
         (let [result (handler context)]
           (if
            (nil? result)
             {::anom/category ::anom/fault
              ::anom/message (format "Query handler returned nil: %s"
                                     (get-in query [:query :query/name]))}
             result))
         {::anom/category ::anom/incorrect
          ::anom/message "Invalid Query: Failed Schema Validation"
          :error/explain (me/humanize (mc/explain query-name query))})
       {::anom/category ::anom/not-found
        ::anom/message "Unknown Query"}))))

