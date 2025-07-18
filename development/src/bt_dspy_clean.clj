(ns bt-dspy-clean
  (:require [ai.obney.grain.behavior-tree.interface :as bt]
            [ai.obney.grain.behavior-tree.interface.protocols :refer [execute-action evaluate-condition success failure]]
            [ai.obney.grain.clj-dspy.interface :as clj-dspy :refer [defsignature]]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [libpython-clj2.python :as py :refer [py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; Initialize Python and import DSPy
(require-python '[dspy :as dspy])

;; ## Custom Behavior Tree Conditions

(defmethod evaluate-condition :has-value [condition-key context]
  (let [blackboard (:blackboard context)
        key (:key context)
        schema-name (:schema context)
        value (bt/get-value blackboard key)]
    (if schema-name
      ;; Schema-based validation using registered schemas
      (and value (m/validate schema-name value))
      ;; Basic truthy + not blank check
      (boolean (and value (not (str/blank? value)))))))

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
  (let [{:keys [signature]} context
        {:keys [output-keys]} (extract-signature-metadata signature)]
    (reduce (fn [acc output-key]
              (let [field-name (name output-key)
                    output-value (py/get-attr result field-name)
                    clj-value (let [v (py/->jvm output-value)]
                                (cond
                                  (map? v) (walk/keywordize-keys v)
                                  :else v))]
                (assoc acc output-key clj-value)))
            {} output-keys)))

(defmethod execute-dspy-operation :predict [_ signature context inputs]
  (let [python-signature (if (var? signature) @signature signature)
        dspy-module (dspy/Predict python-signature)
        result (apply dspy-module (apply concat inputs))
        outputs (extract-outputs-from-result result context)]
    
    ;; Emit predicted event
    (when-let [event-store (:event-store context)]
      (when-let [node-id (:id context)]
        (let [blackboard (:blackboard context)
              blackboard-id (:blackboard-id blackboard)
              event (event-store/->event 
                      {:type :grain.agent/predicted
                       :tags #{[:blackboard blackboard-id]}
                       :body {:node-id node-id
                              :inputs inputs
                              :outputs outputs}})]
          (println (event-store/append event-store {:events [event]})))))
    
    {:success true :outputs outputs}))

(defmethod execute-dspy-operation :chain-of-thought [_ signature context inputs]
  (let [python-signature (if (var? signature) @signature signature)
        dspy-module (dspy/ChainOfThought python-signature)
        result (apply dspy-module (apply concat inputs))
        outputs (extract-outputs-from-result result context)
        reasoning (try 
                    (py/->jvm (py/get-attr result "reasoning"))
                    (catch Exception _ "Reasoning not available"))]
    
    ;; Emit reasoned event
    (when-let [event-store (:event-store context)]
      (when-let [node-id (:id context)]
        (let [blackboard (:blackboard context)
              blackboard-id (:blackboard-id blackboard)
              event (event-store/->event 
                      {:type :grain.agent/reasoned
                       :tags #{[:blackboard blackboard-id]}
                       :body {:node-id node-id
                              :inputs inputs
                              :outputs outputs
                              :reasoning reasoning}})]
          (event-store/append event-store {:events [event]}))))
    
    {:success true :outputs outputs}))

(defmethod execute-action :dspy [_ context]
  (let [{:keys [signature operation log-prefix]} context
        {:keys [input-keys]} (extract-signature-metadata signature)]
    (try
      (let [blackboard (:blackboard context)
            inputs (reduce (fn [acc key]
                             (let [value (bt/get-value blackboard key)]
                               (if value
                                 (assoc acc key value)
                                 (reduced nil))))
                           {} input-keys)]
        (if inputs
          (let [result (execute-dspy-operation operation signature context inputs)]
            (if (:success result)
              (do
                ;; Set all outputs to blackboard
                (doseq [[output-key output-value] (:outputs result)]
                  (bt/set-value blackboard output-key output-value))
                (println (str "✓ " log-prefix) "completed successfully")
                success)
              (do
                (println (str "✗ " log-prefix " - DSPy operation failed"))
                failure)))
          (do
            (println (str "✗ " log-prefix " - missing inputs: " (pr-str input-keys)))
            failure)))
      (catch Exception e
        (println (str "✗ " log-prefix " error:") (.getMessage e))
        failure))))

;; ## Domain Schemas

(defschemas schemas
  {:document/text [:string {:min 1 :desc "Document text"}]
   :document/title [:string {:min 1 :desc "Document title"}]
   :document/key-points [:vector {:desc "List of key points"} :string]
   :document/sentiment [:enum "positive" "negative" "neutral"]
   :document/word-count [:int {:desc "Number of words"}]
   :document/summary
   [:map
    [:title :document/title]
    [:key_points :document/key-points]
    [:sentiment :document/sentiment]
    [:word_count :document/word-count]]})

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


;; ## DSPY Models & Signatures

(defsignature ExtractKeyPoints
  "Extract the main key points from a document"
  {:inputs {:document (:document/text schemas)}
   :outputs {:key_points (:document/key-points schemas)}})

(defsignature AnalyzeSentiment
  "Analyze the sentiment of text and return only: positive, negative, or neutral"
  {:inputs {:document (:document/text schemas)}
   :outputs {:sentiment (:document/sentiment schemas)}})

(defsignature GenerateTitle
  "Generate a concise title for a document"
  {:inputs {:document (:document/text schemas)
            :key_points (:document/key-points schemas)}
   :outputs {:title (:document/title schemas)}})

(defsignature CreateFinalSummary
  "Create a structured document summary from analyzed components"
  {:inputs {:title (:document/title schemas)
            :key_points (:document/key-points schemas)
            :sentiment (:document/sentiment schemas)
            :word_count (:document/word-count schemas)}
   :outputs {:final_summary (:document/summary schemas)}})

;; ## BT Actions

(defmethod execute-action :calculate-word-count [_ context]
  (let [blackboard (:blackboard context)
        document (bt/get-value blackboard :document)
        word-count (count (str/split document #"\s+"))]
    (bt/set-value blackboard :word_count word-count)
    (println "✓ Calculated word count:" word-count)
    success))

(defmethod execute-action :test-event-store-access [_ context]
  (let [event-store (:event-store context)]
    (if event-store
      (do
        (println "✓ Event-store is accessible in context")
        success)
      (do
        (println "✗ Event-store NOT accessible in context")
        failure))))



;; ## Behavior Tree

(def document-analysis-tree
  "Behavior tree for document analysis and summarization using DSPy"
  [:sequence
   ;; 1. Validate input document exists with schema validation
   [:condition
    {:key :document
     :schema :document/text}
    :has-value]

   ;; 2. Extract key information in parallel using declarative DSPy actions
   [:parallel {:success-threshold 2}
    [:retry {:max-retries 3}
     [:action
      {:id :extract-key-points
       :signature #'ExtractKeyPoints
       :operation :chain-of-thought
       :log-prefix "Extracting key points"}
      :dspy]]
    [:retry {:max-retries 3}
     [:action
      {:id :analyze-sentiment
       :signature #'AnalyzeSentiment
       :operation :chain-of-thought
       :log-prefix "Analyzing sentiment"}
      :dspy]]]

   ;; 3. Generate title after key points are extracted
   [:sequence
    [:condition
     {:key :key_points
      :schema :document/key-points}
     :has-value]
    [:action
     {:id :generate-title
      :signature #'GenerateTitle
      :operation :chain-of-thought
      :log-prefix "Generating title"}
     :dspy]]

   ;; 4. Calculate word count deterministically
   [:action :calculate-word-count]

   ;; 5. Create final summary using DSPy
   [:sequence
    [:condition
     {:key :title
      :schema :document/title}
     :has-value]
    [:condition
     {:key :sentiment
      :schema :document/sentiment}
     :has-value]
    [:condition
     {:key :word_count
      :schema :document/word-count}
     :has-value]
    [:action
     {:id :create-final-summary
      :signature #'CreateFinalSummary
      :operation :chain-of-thought
      :log-prefix "Creating final summary"}
     :dspy]]

   [:condition
    {:key :final_summary
     :schema :document/summary}
    :has-value]])

;; ## Entrypoint

(defn analyze-document
  "Analyze a document using behavior tree orchestrated DSPy pipeline."
  [event-store document]
  (println "🚀 Starting document analysis with behavior tree...")
  (println "📄 Document:" (subs document 0 (min 100 (count document))) "...")

  ;; Execute with initial blackboard state (single event internally)
  (let [{:keys [result blackboard]} (bt/execute
                                      event-store
                                      document-analysis-tree
                                      :initial-blackboard {:document document})]
    (println "🏁 Analysis complete with result:" result)
    (if (= result success)
      (bt/get-value blackboard :final_summary)
      (do
        (println "❌ Analysis failed")
        nil))))

;; ## Example Usage

(def sample-doc "Language models (LMs) built on the transformer neural network architecture and trained
on a next-token prediction objective have demonstrated impressive capabilities across a
wide spectrum of tasks in Natural Language Processing (NLP) with adjacent fields such
as Computer Vision. The strengths of these models have been sufficiently great that some
commentators have suggested that these models will, by themselves, suffice for building
generally capable intelligent agents: for these people an end-to-end transformer really is
“all you need.” But as we see these models deployed in real-world settings, it becomes clear
that we need to start thinking about language models in the context of the larger systems
of which they are a part (Zaharia et al., 2024). While there is a growing appreciation of the
need to think of language models as primitive elements to be composed with other software
components to build functional systems, there is not yet broad agreement on how best to do
this.
Outside of NLP, other fields have significant experience with the problem of building robust
systems composed of simpler building blocks or modules. Practitioners from both Game
Artificial Intelligence and Robotics have for several years used the behavior tree abstraction
¨
to build AI systems (Colledanchise &
Ogren, 2017). In the behavior tree framework, one
first identifies the “atomic” actions their system should perform, and then combines these
simple behaviors using a small set of well-defined control primitives. The result is a tree
structure in which every node of the tree is either an action taken by the system or a node
responsible for manipulating the flow of control. Crucially, the interfaces between these
nodes are enforced to be extremely simple: parent nodes instruct their children to run (called
ticking their children), and children report a one-bit status back to their parent. This simple
interface leads to the theoretical result that behavior trees are optimally modular with respect
to a large class of reactive control architectures which includes finite state machines and
decision trees (Biggar et al., 2022). In practice, the modularity of behavior trees has enabled
designers to build libraries of composable subtrees that can be freely reused and recombined
as applications dictate.
1
These insights of the Game Development and Robotics communities should be used to
improve the performance and scalability of agents based on language models, particularly
in the “small local model” regime where models are deployed outside of data centers on
constrained devices such as phones, personal computers, and mobile robots. To this end,
we introduce the Dendron library for programming intelligent agents using a combination
of language models and behavior trees. Dendron makes it easy to build behavior trees
that use language models and multimodal models to implement behaviors and logical
conditions that make use of natural language, leading to fluid acting and decision-making
that is otherwise hard to achieve programmatically. Relying on theoretical results proved
for behavior trees, Dendron also enables the construction of control structures that are able
to provide safety guarantees regarding the execution of subtrees based on language models.
In this report, we first review transformer-based language models, setting some notation
and identifying a number of shortcomings in systems that rely primarily on language
models operating end-to-end without additional structure. Then we introduce behavior
trees, defining the core constructions that are useful for designing and reasoning about
programs built using behavior trees. We then that Dendron integrates language models
into a behavior tree framework, and give three case studies demonstrating how several
natural language tasks can be easily developed using the tools provided by Dendron. We
conclude by reviewing opportunities for extending the behavior tree framework to build
more capable intelligent agents.")

(def lm (dspy/LM "openai/gpt-4o-mini" :cache false))

;; ## Run the example

(comment
  ;;1. Configure your LLM (you'll need API keys) 
  (dspy/configure :lm lm)

  ;;2. Start the event store (in-memory for this example)
  (def event-store (event-store/start {:conn {:type :in-memory}}))

  ;;3. Run the complete example
  (analyze-document event-store sample-doc)


  (into [] (event-store/read
            event-store
            {:types #{:grain.agent/predicted
                      :grain.agent/reasoned}}))

  
  "")


