(ns ai.obney.grain.behavior-tree-v2.core.nodes
  (:require [ai.obney.grain.behavior-tree-v2.interface.protocol :as p :refer [opts+children]]))

(defmethod p/tick :default
  [node _context]
  (throw (ex-info "Node type not implemented" {:node node})))

;;
;; Sequence
;;

(defmethod p/tick :sequence
  [node context]
  (loop [[child-node :as children] (:children node)]
    (if-not child-node
      p/success
      (let [result (p/tick child-node context)]
        (case result
          :success (recur (rest children))
          :failure p/failure
          :running p/running)))))

(defmethod p/build :sequence
  [node-type args] 
  (let [[opts children] (opts+children args)] 
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Fallback
;;

(defmethod p/tick :fallback
  [node context]
  (loop [[child-node :as children] (:children node)]
    (if-not child-node
      p/failure
      (let [result (p/tick child-node context)]
        (case result
          :success p/success
          :failure (recur (rest children))
          :running p/running)))))

(defmethod p/build :fallback
  [node-type args]
  (let [[opts children] (opts+children args)]
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Parallel 
;;

(defmethod p/tick :parallel
  [{:keys [success-threshold children] :as _node} context]
  (let [success-threshold (or success-threshold (count children))
        futures (mapv #(future (p/tick % context)) children)
        results (mapv deref futures)
        success-count (count (filter #(= % p/success) results))
        failure-count (count (filter #(= % p/failure) results))]
    (cond
      (>= success-count success-threshold) p/success
      (> failure-count (- (count children) success-threshold)) p/failure
      :else p/running)))

(defmethod p/build :parallel
  [node-type args]
  (let [[opts children] (opts+children args)]
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Condition 
;;

(defmethod p/tick :condition
  [{:keys [condition-fn opts] :as _node} context]
  (if (condition-fn (assoc context :opts opts))
    p/success
    p/failure))

(defmethod p/build :condition
  [node-type args]
  (let [[opts children] (opts+children args)]
    {:type node-type
     :opts opts
     :condition-fn (first children)}))

;;
;; Action
;;

(defmethod p/tick :action
  [{:keys [action-fn opts] :as _node} context]
  (action-fn (assoc context :opts opts)))

(defmethod p/build :action
  [node-type args]
  (let [[opts children] (opts+children args)]
    {:type node-type
     :opts opts
     :action-fn (first children)}))



  (comment

    (p/tick 
     {:type :action
      :opts {:hello "World"}
      :action-fn (fn [context]
                   (println (:opts context))
                   p/success)}
     {})
    


    "")