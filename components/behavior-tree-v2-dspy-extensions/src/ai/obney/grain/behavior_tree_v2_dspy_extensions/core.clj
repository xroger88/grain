(ns ai.obney.grain.behavior-tree-v2-dspy-extensions.core
  (:require [ai.obney.grain.behavior-tree-v2.interface :as bt2]
            [ai.obney.grain.behavior-tree-v2.interface.protocol :as btp]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [libpython-clj2.python :as py :refer [py.-]]
            [libpython-clj2.require :refer [require-python]]
            [clojure.walk :as walk]))

;; Initialize Python and import DSPy
(require-python '[dspy :as dspy])

;; ## DSPY Integration

(defn extract-signature-metadata
  "Extract input and output keys from a signature var's metadata"
  [signature-var]
  (let [metadata (meta signature-var)
        dspy-meta (get metadata :dspy/signature)
        inputs (keys (:inputs dspy-meta))
        outputs (keys (:outputs dspy-meta))]
    {:input-keys inputs
     :output-keys outputs}))

(defmulti execute-dspy-operation
  "Execute DSPy operation: create module, run it, and emit events"
  (fn [operation _signature _context _inputs] operation))

(defn extract-outputs-from-result
  "Extract outputs from DSPy result and convert to Clojure data"
  [result context]
  (let [{:keys [signature]} (:opts context)
        {:keys [output-keys]} (extract-signature-metadata signature)
        result* (py.- result :outputs)]
    (reduce (fn [acc output-key]
              (let [field-name (name output-key)
                    output-value (py/get-attr result* field-name)
                    clj-value (let [v (py/->jvm output-value)]
                                (cond
                                  (map? v) (walk/keywordize-keys v)
                                  :else v))]
                (assoc acc output-key clj-value)))
            {} output-keys)))

(defschemas agent-events
  {:grain.agent/predicted
   [:map
    [:node-id :keyword]
    [:inputs :map]
    [:outputs :map]]

   :grain.agent/reasoned
   [:map
    [:node-id :keyword]
    [:inputs :map]
    [:outputs :map]
    [:reasoning :string]]})

(defmethod execute-dspy-operation :predict [_ signature context inputs]
  (let [python-signature (if (var? signature) @signature signature)
        dspy-module (dspy/Predict python-signature)
        result (apply dspy-module (apply concat inputs))
        outputs (extract-outputs-from-result result context)]

    ;; Emit predicted event
    (when-let [event-store (:event-store context)]
      (when-let [node-id (get-in context [:opts :id])]
        (let [event (event-store/->event
                     {:type :grain.agent/predicted
                      :body {:node-id node-id
                             :inputs inputs
                             :outputs outputs}})]
          (event-store/append event-store {:events [event]}))))

    {:outputs outputs}))

(defmethod execute-dspy-operation :chain-of-thought [_ signature context inputs]
  (let [python-signature (if (var? signature) @signature signature)
        dspy-module (dspy/ChainOfThought python-signature)
        result (apply dspy-module (apply concat inputs))
        outputs (extract-outputs-from-result result context)
        reasoning (py.- result :reasoning)]

    ;; Emit reasoned event
    (when-let [event-store (:event-store context)]
      (when-let [node-id (get-in context [:opts :id])]
        (let [event (event-store/->event
                     {:type :grain.agent/reasoned
                      :body {:node-id node-id
                             :inputs inputs
                             :outputs outputs
                             :reasoning reasoning}})]
          (event-store/append event-store {:events [event]}))))

    {:outputs outputs}))

(defn dspy [{{:keys [id signature operation]} :opts
             :keys [st-memory]
             :as context}]
  (let [{:keys [input-keys]} (extract-signature-metadata signature)]
    (try
      (let [state (cond-> @st-memory
                    (:lt-memory context) (merge (btp/latest (:lt-memory context))))
            inputs (reduce (fn [acc key]
                             (let [value (get state key)]
                               (if value
                                 (assoc acc key value)
                                 acc)))
                           {} input-keys)]
        (if inputs
          (let [result (execute-dspy-operation operation signature context {:inputs inputs})]
            (doseq [[output-key output-value] (:outputs result)]
              (swap! st-memory assoc output-key output-value))
            (println (str "✓ " id) "completed successfully")
            bt2/success)
          (do
            (println (str "✗ " id " - missing inputs: " (pr-str input-keys)))
            bt2/failure)))
      (catch Exception e
        (println (str "✗ " id " error:") (.getMessage e))
        bt2/failure))))
