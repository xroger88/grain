(ns example-app-demo
  (:require [ai.obney.grain.example-base.core :as service]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.event-store-v2.core.in-memory]
            [ai.obney.grain.event-store-postgres-v2.interface]
            [ai.obney.grain.example-service.interface.read-models :as rm]
            [ai.obney.grain.time.interface :as time]
            [clj-http.client :as http]))

(comment

  ;;
  ;; Start Service
  ;;
  (do
    (def service (service/start))
    (def context (::service/context service))
    (def event-store (:event-store context)))


  ;;
  ;; Stop Service ;;
  ;;
  (service/stop service)

  ""
  )


(comment

  ;; Interact internally in the REPL with out HTTP

  (try
    (cp/process-command
     (assoc context
            :command {:command/name :example/create-counter
                      :command/timestamp (time/now)
                      :command/id (random-uuid)
                      :name "Counter A"}))
    (catch Exception e (ex-data e)))

  (into [] (es/read event-store {}))

  (def counters
    (->> (qp/process-query
          (assoc context
                 :query {:query/name :example/counters
                         :query/timestamp (time/now)
                         :query/id (random-uuid)}))
         :query/result))


  (def counter
    (->> (qp/process-query
          (assoc context
                 :query {:query/name :example/counter
                         :query/timestamp (time/now)
                         :query/id (random-uuid)
                         :counter-id (:counter/id (first counters))}))
         :query/result))



  (cp/process-command
   (assoc context
          :command {:command/name :example/increment-counter
                    :command/timestamp (time/now)
                    :command/id (random-uuid)
                    :counter-id (:counter/id counter)}))


  (rm/root context)

  (into [] (es/read event-store {}))


  ""
  )

(comment
  ;; Interact with the service via HTTP

  ;; Create a counter
  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :json
       :form-params {:command {:command/name :example/create-counter
                               :name "Counter C"}}}))
    (catch Exception e (ex-data e)))

  ;; Get all counters
  (def counters
    (try
      (:body
       (http/post
        "http://localhost:8080/query"
        {:content-type :json
         :as :json
         :form-params {:query {:query/name :example/counters}}}))
      (catch Exception e (ex-data e))))

  ;; Increment first counter

  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :json
       :form-params {:command {:command/name :example/increment-counter
                               :counter-id (-> counters first :id)}}}))
    (catch Exception e (ex-data e)))

  ;; Decrement a counter by ID

  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :json
       :form-params {:command {:command/name :example/decrement-counter
                               :counter-id (-> counters first :id)}}}))
    (catch Exception e (ex-data e)))


  

  

  ""
  )