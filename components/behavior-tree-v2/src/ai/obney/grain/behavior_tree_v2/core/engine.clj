(ns ai.obney.grain.behavior-tree-v2.core.engine
  (:require [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]
            [ai.obney.grain.behavior-tree-v2.core.long-term-memory :as ltm]
            [ai.obney.grain.behavior-tree-v2.core.nodes]
            [malli.core :as m]))

(defn build
  "Build a behavior tree from a config vector."
  [[node-type & args :as _config] 
   {:keys [event-store queries read-model-fn st-memory] :as context}]
  {:tree (p/build node-type args)
   :context (cond-> context
              :always (assoc :st-memory (atom (or st-memory {})))
              (and event-store queries read-model-fn)
              (assoc :lt-memory (ltm/->long-term-memory context)))})

(defn run
  "Run the behavior tree with the given context."
  [{:keys [tree context] :as _bt}]
  (p/tick tree context))

;; ## Custom Behavior Tree Conditions

(defn st-memory-has-value?
  [{{:keys [path schema]} :opts
    :keys [st-memory]}]
  (let [st-memory-state @st-memory]
    (m/validate
     schema
     (if path
       (get-in st-memory-state path)
       st-memory-state))))

(defn lt-memory-has-value?
  [{{:keys [path schema]} :opts
    :keys [lt-memory]}]
  (let [lt-memory-state (p/latest lt-memory)]
    (m/validate
     schema
     (if path
       (get-in lt-memory-state path)
       lt-memory-state))))

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


  "")