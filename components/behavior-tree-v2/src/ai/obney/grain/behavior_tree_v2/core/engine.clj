(ns ai.obney.grain.behavior-tree-v2.core.engine
  (:require [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]
            [ai.obney.grain.behavior-tree-v2.core.long-term-memory :as ltm]
            [ai.obney.grain.behavior-tree-v2.core.nodes]))

(defn build
  "Build a behavior tree from a config vector."
  [[node-type & args :as _config] 
   {:keys [_event-store st-memory] :as context}]
  {:tree (p/build node-type args)
   :context (assoc context
                   :lt-memory (ltm/->long-term-memory context)
                   :st-memory (atom (or st-memory {})))})

(defn run
  "Run the behavior tree with the given context."
  [{:keys [tree context] :as _bt}]
  (p/tick tree context))

(comment

  (def running-seq
    [:sequence
     [:action (fn [_] p/running)]
     [:action (fn [_] p/success)]
     [:action (fn [_] p/failure)]])

  (def success-seq
    [:sequence
     [:action (fn [_] p/success)]
     [:action (fn [_] p/success)]])

  (def failure-seq
    [:sequence
     [:action (fn [_] p/failure)]
     [:action (fn [_] p/success)]])

  (def running-fallback
    [:fallback
     [:action (fn [_] p/running)]
     [:action (fn [_] p/success)]
     [:action (fn [_] p/failure)]])

  (def success-fallback
    [:fallback
     [:action (fn [_] p/success)]
     [:action (fn [_] p/failure)]])

  (def failure-fallback
    [:fallback
     [:action (fn [_] p/failure)]
     [:action (fn [_] p/failure)]])



  (require '[ai.obney.grain.event-store-v2.interface :as es])

  (def event-store
    (es/start {:conn {:type :in-memory}}))

  (run
   [:sequence
    [:action (fn [context] (swap! (:st-memory context) assoc :hello "world") p/success)]
    [:condition (fn [context] (= "world" (get @(:st-memory context) :hello)))]]
   {:event-store event-store
    :read-model-fn (fn [_ _] {})
    :queries []})

  "")