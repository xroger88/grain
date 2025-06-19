(ns example-app-demo
  (:require [ai.obney.grain.example-base.core :as service]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store.interface :as es]
            [ai.obney.grain.example-service.interface.read-models :as rm]
            [ai.obney.grain.time.interface :as time]
            [clj-http.client :as http]
            [ai.obney.grain.event-store.interface :as event-store]))

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

  (cp/process-command
   (assoc context
          :command {:command/name :example/create-counter
                    :command/timestamp (time/now)
                    :command/id (random-uuid)
                    :name "Counter B"}))

  (es/get-events event-store {})

  (qp/process-query
   (assoc context
          :query {:query/name :example/counters
                  :query/timestamp (time/now)
                  :query/id (random-uuid)}))

  (qp/process-query
   (assoc context
          :query {:query/name :example/counter
                  :query/timestamp (time/now)
                  :query/id (random-uuid)
                  :counter-id #uuid "1de099f1-d361-4e62-9452-53f6ccf2452f"}))

  (cp/process-command
   (assoc context
          :command {:command/name :example/increment-counter
                    :command/timestamp (time/now)
                    :command/id (random-uuid)
                    :counter-id #uuid "d2ea8475-5bac-4050-9317-6e5d42dbe729"}))

  
  (rm/root context)



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