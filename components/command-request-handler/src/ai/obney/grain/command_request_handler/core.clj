(ns ai.obney.grain.command-request-handler.core
  (:require [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.command-schema.interface :as command-schema]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.core.async :as async]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]
            [malli.core :as mc]
            [malli.transform :as mt]
            [malli.error :as me]
            [io.pedestal.http.body-params :as body-params]
            [clojure.data.json :as json]))

(defn process-command-result-dispatch
  [result]
  (::anom/category result))

(defmulti process-command-result process-command-result-dispatch)

(defmethod process-command-result ::anom/incorrect
  [{:keys [::anom/message error/explain]}]
  {:status 400
   :body {:message message
          :explain explain}})

(defmethod process-command-result ::anom/not-found
  [{:keys [::anom/message]}]
  {:status 404
   :body {:message message}})

(defmethod process-command-result ::anom/forbidden
  [{:keys [::anom/message]}]
  {:status 409
   :body {:message message}})

(defmethod process-command-result ::anom/conflict
  [{:keys [::anom/message]}]
  {:status 403
   :body {:message message}})

(defmethod process-command-result :default
  [{:keys [::anom/message]}]
  {:status 500
   :body {:message message}})

(defmethod process-command-result nil
  [result]
  {:status 200
   :body (or (:command/result result) "OK")})

(def json-transformer
  (mt/transformer
   mt/json-transformer))

(defn decode-command
  [command]
  (as-> command x
    (clojure.walk/keywordize-keys x)
    (assoc x :command/id (random-uuid))
    (assoc x :command/timestamp (time/now))
    (mc/decode ::command-schema/command x json-transformer)))

(defn prep-response 
  [response]
  (-> response
      (assoc-in [:headers "Content-Type"] "application/json")
      (update :body json/write-str)))

(defn handle-command [{:keys [command-processor] :as config} {:keys [request] :as context}]
  (async/go
    (u/trace
     ::handle-command
     [::request request]
     (try
       (let [return-chan (async/chan)
             command (decode-command (get-in request [:json-params :command]))]
         (if-let [error (me/humanize (mc/explain ::command-schema/command command))]
           (assoc context :response
                  (prep-response
                   (process-command-result
                    {::anom/category ::anom/incorrect
                     ::anom/message "Invalid Command"
                     :error/explain error})))
           (let [result (async/<! (async/thread (cp/process-command (assoc config :command command))))]
             (when (anomaly? result)
               (u/log ::anomaly ::anom/anomaly result))
             (assoc context :response (prep-response (process-command-result result))))))
       (catch Exception e (u/log ::error :error e))))))

(defn interceptor
  [config]
  {:name ::command-request-handler
   :enter (partial #'handle-command config)})

(defn routes
  [{:keys [_event-store] :as config}]
  #{["/command" :post [(body-params/body-params) (interceptor config)] :route-name :command]})
