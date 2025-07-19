(ns ai.obney.grain.event-store-v2.core.in-memory
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v2.interface.protocol :as p]
            [ai.obney.grain.event-store-v2.core :refer [->event]]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]
            [clojure.set :as set]
            [clj-uuid :as uuid]))

(defn start
  [_config]
  (ref {:events []}))

(defn stop
  [state]
  (dosync (ref-set state nil)))

(defn read
  [event-store {:keys [tags types as-of after] :as args}]
  (u/trace
   ::read
   [:args args
    :metric/name "GrainReadEvents"]
   (let [filtered-events (->> (-> event-store :state deref :events)
                              (filter
                               (fn [event]
                                 (and
                                  (or (not tags)
                                      (set/subset? tags (:event/tags event)))
                                  (or (not types)
                                      (contains? types (:event/type event)))
                                  (cond
                                    as-of (or (uuid/< (:event/id event) as-of)
                                              (uuid/= (:event/id event) as-of))
                                    after (uuid/> (:event/id event) after)
                                    :else true)))))]
     (reify
       ;; Support streaming reduction with init value
       clojure.lang.IReduceInit
       (reduce [_ f init]
         (reduce f init filtered-events))
       ;; Support streaming reduction without init value  
       clojure.lang.IReduce
       (reduce [_ f]
         (let [reduced-result 
               (reduce
                (fn [acc event]
                  (if (= acc ::none)
                    event
                    (f acc event)))
                ::none
                filtered-events)]
           (if (= reduced-result ::none)
             (f)  ; Empty collection case
             reduced-result)))))))

(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [events tx-metadata]}]
  (u/trace
   ::append
   [:grain/event-ids (map :event/id events)
    :metric/name "GrainAppendEvents"]
   (let [tx (->event
             {:type :grain/tx
              :body {:event-ids (set (mapv :event/id events))
                     :metadata tx-metadata}})]
     (dosync
      (if cas
        (let [events* (read event-store cas)
              pred-result (predicate-fn events*)]
          (if pred-result
            (alter (:state event-store) update :events into events)
            (let [anomaly {::anom/category ::anom/conflict
                           ::anom/message "CAS failed"
                           :cas cas}]
              (u/log :grain/cas-failed :anomaly anomaly)
              anomaly)))
        (alter (:state event-store) update :events into (conj events tx)))))))

  (defrecord InMemoryEventStore [config]
    p/EventStore

    (start [this]
      (assoc this :state (start config)))

    (stop [this]
      (stop (:state this))
      (dissoc this :state))

    (append [this args]
      (append this args))

    (read [this args]
      (read this args)))

  (defmethod p/start-event-store :in-memory
    [config]
    (p/start (->InMemoryEventStore config)))


  (comment
    (def es (p/start-event-store {:conn {:type :in-memory}}))

    (p/append
     es
     {:events
      [(->event
        {:type :hello-world
         :tags #{[:user (random-uuid)]}
         :body {:message "Hello, world!"}})]
      :tx-metadata {:foo :boz}
      :cas {:predicate-fn (fn [events] (not (empty? events)))}})

    (require '[clj-uuid :as uuid])

    (p/read 
     es
     {#_#_:after #uuid "0197b965-7f45-700a-b2fc-87f0921ea7fa"}) 


    "")