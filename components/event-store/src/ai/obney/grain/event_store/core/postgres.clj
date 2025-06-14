(ns ai.obney.grain.event-store.core.postgres
  (:require [ai.obney.grain.event-store.core.protocol :as p :refer [EventStore]]
            [ai.obney.grain.event-schema.interface :as schemas]
            [next.jdbc :as jdbc]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [clojure.data.json :as json]
            [ai.obney.grain.time.interface :as time]
            [malli.transform :as mt]
            [cognitect.anomalies :as anom]
            [clojure.string :as string]))

;; -------------------------- ;;
;; Event Store Initialization ;;
;; -------------------------- ;;

(defn init-idempotently
  [{::keys [connection-pool] :as _event-store}]
  (u/trace
   ::initializing-event-store-idempotently
   []
   (jdbc/with-transaction [conn connection-pool]
     (doseq [statement ["CREATE SCHEMA IF NOT EXISTS obneyai;"

                        ;; Table
                        "CREATE TABLE IF NOT EXISTS obneyai.events
                         (event_id UUID PRIMARY KEY,
                          event_name TEXT NOT NULL,
                          entity_id UUID NOT NULL,
                          entity_version SERIAL NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW (),
                          event JSONB NOT NULL);"

                        "CREATE TABLE IF NOT EXISTS obneyai.global_lock (
                          id INTEGER PRIMARY KEY
                         )"

                        "INSERT INTO obneyai.global_lock (id) VALUES (1) ON CONFLICT DO NOTHING;"

                        ;; Indices
                        "CREATE INDEX IF NOT EXISTS idx_event_name on obneyai.events (event_name);"
                        "CREATE INDEX IF NOT EXISTS idx_entity_id_event_name on obneyai.events (entity_id, event_name);"
                        "CREATE INDEX IF NOT EXISTS idx_entity_version on obneyai.events (entity_id, entity_version);"
                        "CREATE INDEX IF NOT EXISTS idx_events_entity_id ON obneyai.events (entity_id);"
                        "CREATE INDEX IF NOT EXISTS idx_events_event_gin ON obneyai.events USING GIN (event);"]]

       (jdbc/execute! conn [statement])))))



;; --------------------------- ;;
;; Integrant / Lifecycle Setup ;;
;; --------------------------- ;;

(defn start
  [{::keys [_server-name _port-number _username _password _database-name] :as config}]
  (u/trace
   ::starting-event-store
   []
   (let [config* (assoc config :adapter "postgresql")
         system (ig/init
                 {::config config*
                  ::connection-pool {::config (ig/ref ::config)}})]
     (init-idempotently system)
     system)))

(defn stop
  [event-store]
  (u/trace
   ::stopping-event-store
   []
   (ig/halt! event-store)))



;; ---------------;;
;; Integrant keys ;;
;; -------------- ;;

(defmethod ig/init-key ::config [_ config]
  config)

(defmethod ig/init-key ::connection-pool [_ {::keys [config]}]
  (try
    (hikari/make-datasource config)
    (catch Throwable t
      (u/log ::error-creating-connection-pool :error t)
      (throw t))))

(defmethod ig/halt-key! ::connection-pool [_ connection-pool]
  (hikari/close-datasource connection-pool))



;; ------------- ;;
;; Functionality ;;
;; ------------- ;;

(defn key-fn
  [k]
  (if (qualified-keyword? k)
    (str (namespace k) "/" (name k))
    (str (name k))))

(defn current-entity-version
  [event-store entity-id]
  (or (:max (jdbc/execute-one!
             (get-in event-store [:state ::connection-pool])
             ["SELECT MAX(entity_version) FROM obneyai.events WHERE entity_id = ?"
              entity-id]))
      1))

(defn get-events
  [event-store {:keys [entity-id selection ignore]}]
  (let [base-query "SELECT event FROM obneyai.events"
        clauses (cond-> []
                  entity-id (conj "entity_id = ?")
                  selection (conj "event_name = ANY(?)")
                  ignore (conj "event_name <> ALL(?)"))
        query (if (seq clauses)
                (str base-query " WHERE " (string/join " and " clauses) " ORDER BY event_id")
                base-query)
        params (cond-> []
                 entity-id (conj entity-id)
                 selection (conj (into-array String (mapv key-fn selection)))
                 ignore (conj (into-array String (mapv key-fn ignore))))]
    (reduce
     (fn [acc row]
       (conj acc
             (-> row
                 :events/event
                 str
                 (json/read-str :key-fn keyword)
                 (update :event/name keyword)
                 (update :event/timestamp time/now-from-str)
                 (update :event/entity-id #(java.util.UUID/fromString %)))))
     []
     (jdbc/execute!
      (get-in event-store [:state ::connection-pool])
      (into [query] params)))))

(defn insert-events
  [conn events]
  (jdbc/execute-batch!
   conn
   "INSERT INTO obneyai.events 
    (event_id, event_name, entity_id, event) 
    VALUES (?, ?, ?, ?::jsonb)"
   (map (fn [event]
          [(:event/id event)
           (key-fn (:event/name event))
           (:event/entity-id event)
           (clojure.data.json/write-str
            (-> event
                (update :event/timestamp str)
                (update :event/name key-fn))
            :key-fn key-fn)])
        events)
   {:batch-size 100})) ;; Configurable batch size

(defn store-events
  [event-store
   {{:keys [entity-id selection ignore read-model-fn pred-fn] :as cas} :cas
    :keys [events]}]
  (u/trace
   ::storing-events
   [::events events]
   (jdbc/with-transaction
     [conn (get-in event-store [:state ::connection-pool])]
     (if cas
       (let [_ (jdbc/execute! conn ["SET LOCAL lock_timeout = '5000ms'"])
             _ (jdbc/execute! conn ["SELECT id FROM obneyai.global_lock FOR UPDATE"])
             events* (get-events event-store
                                 (cond-> {:entity-id entity-id}
                                   selection (assoc :selection selection)
                                   ignore (assoc :ignore ignore)))
             read-model (read-model-fn events*)
             pred-result (pred-fn read-model)]
         (if pred-result
           (insert-events conn events)
           (let [anomaly  {::anom/category ::anom/conflict
                           ::anom/message "CAS failed"
                           ::cas cas}]
             (u/log ::cas-failed :anomaly anomaly)
             anomaly)))
       (insert-events conn events)))))



;; ----------------- ;;
;; Record Definition ;;
;; ----------------- ;;

(defrecord PostgresEventStore [config]
  EventStore

  (start [this]
    (assoc this :state (start config)))

  (stop [this]
    (stop (:state this))
    (dissoc this :state))

  (store-events [this args]
    (store-events this args))

  (get-events [this args]
    (get-events this args))

  (current-entity-version
    [this entity-id]
    (current-entity-version this entity-id)))


(comment

  (u/start-publisher! {:type :console
                       :pretty? true})

  (def rec (p/start
            (->PostgresEventStore
             {:server-name "localhost"
              :port-number 5432
              :username "postgres"
              :password "password"
              :database-name "obneyai"})))

  (-> (get-events rec {:entity-id #uuid "98ba9843-1100-5c06-b701-0eaf9a390ded"}))

  (current-entity-version rec (random-uuid))

  (p/stop rec)

  (jdbc/execute!
   (get-in rec [:state ::connection-pool])
   ["INSERT INTO 
     obneyai.events 
    (event_id, event_name, entity_id, entity_version, event) 
     VALUES 
    (?, 
     ?,
     ?,
     ?,
     ?::jsonb)"
    (random-uuid)
    "vector-store/namespace-created-v1"
    (random-uuid)
    2
    (clojure.data.json/write-str
     {:event/id (random-uuid)
      :event/entity-id (random-uuid)
      :event/entity-version 2
      :event/name "vector-store/namespace-created-v1"
      :event/timestamp (str (ai.obney.grain.time.interface/now))
      :namespaces ["test1" "test2"]}
     :key-fn #(if (qualified-keyword? %)
                (str (namespace %) "/" (name %))
                (str (name %))))])



  {:event/id (random-uuid)
   :event/entity-id (random-uuid)
   :event/entity-version 2
   :event/name :vector-store/namespace-created-v1
   :event/timestamp (ai.obney.grain.time.interface/now)
   :namespaces ["test1" "test2"]}


  (store-events
   rec
   {:events [{:event/id (random-uuid)
              :event/entity-id (random-uuid)
              :event/entity-version 1
              :event/name :vector-store/namespace-created-v1
              :event/timestamp (ai.obney.grain.time.interface/now)
              :namespaces ["test1" "test2"]}]})





  (require '[malli.core :as m])


  (defn event-transformer
    []
    (mt/transformer
     {:name ::event
      :encoders {::schemas/entity-id str
                 ::schemas/event-id str
                 ::schemas/event-name key-fn
                 ::schemas/event-timestamp str}
      :decoders {::schemas/entity-id #(java.util.UUID/fromString %)
                 ::schemas/event-id #(java.util.UUID/fromString %)
                 ::schemas/event-name keyword
                 ::schemas/event-timestamp #(ai.obney.grain.time.interface/now-from-str %)}}))

  (m/encode :ai.obney.grain.event-schema.interface/event
            {:event/id (random-uuid)
             :event/entity-id (random-uuid)
             :event/entity-version 1
             :event/name :vector-store/namespace-created-v1
             :event/timestamp (ai.obney.grain.time.interface/now)
             :namespaces ["test1" "test2"]}
            (mt/json-transformer))






  "")