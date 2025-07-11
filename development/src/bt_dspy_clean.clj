(ns bt-dspy-clean
  (:require [ai.obney.grain.behavior-tree.interface :as bt]
            [ai.obney.grain.behavior-tree.interface.protocols :refer [execute-action evaluate-condition success failure]]
            [ai.obney.grain.clj-dspy.interface :as clj-dspy :refer [defsignature defmodel]]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [ai.obney.grain.event-store-v2.core.in-memory]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [libpython-clj2.python :as py :refer [py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

;; Initialize Python and import DSPy
(require-python '[dspy :as dspy])

;; =============================================================================
;; Custom Behavior Tree Conditions
;; =============================================================================

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

(defmulti create-dspy-module (fn [operation _] operation))

(defmethod create-dspy-module :predict [_ signature]
  (dspy/Predict signature))

(defmethod create-dspy-module :chain-of-thought [_ signature]
  (dspy/ChainOfThought signature))

(defmethod create-dspy-module :react [_ signature]
  (dspy/ReAct signature))

(defmethod create-dspy-module :program-of-thought [_ signature]
  (dspy/ProgramOfThought signature))

(defn extract-signature-metadata
  "Extract input and output keys from a signature var's metadata"
  [signature-var]
  (let [metadata (meta signature-var)
        dspy-meta (get metadata :dspy/signature)
        inputs (keys (:inputs dspy-meta))
        outputs (keys (:outputs dspy-meta))]
    {:input-keys inputs
     :output-keys outputs}))

(defmethod execute-action :dspy [_ context]
  (let [{:keys [signature operation log-prefix]} context
        {:keys [input-keys output-keys]} (extract-signature-metadata signature)]
    (try
      (let [blackboard (:blackboard context)
            inputs (reduce (fn [acc key]
                             (let [value (bt/get-value blackboard key)]
                               (if value
                                 (assoc acc key value)
                                 (reduced nil))))
                           {} input-keys)]
        (if inputs
          (let [;; Get the actual Python signature class from the var
                python-signature (if (var? signature) @signature signature)
                dspy-module (create-dspy-module operation python-signature)
                result (apply dspy-module (apply concat inputs))]
            ;; Set all outputs to blackboard
            (doseq [output-key output-keys]
              (let [field-name (name output-key)  ; Convert keyword to string
                    output-value (py/get-attr result field-name)
                    ;; Convert Python objects to Clojure data structures
                    clj-value (py/->jvm output-value)]
                (bt/set-value blackboard output-key clj-value)))
            (println (str "✓ " log-prefix) "completed successfully")
            success)
          (do
            (println (str "✗ " log-prefix " - missing inputs: " (pr-str input-keys)))
            failure)))
      (catch Exception e
        (println (str "✗ " log-prefix " error:") (.getMessage e))
        failure))))

;; =============================================================================
;; Schemas
;; =============================================================================

(defschemas schemas
  {:document/text [:string {:min 1 :desc "Document text"}]
   :document/title [:string {:min 1 :desc "Document title"}]
   :document/key-points [:vector {:desc "List of key points"} :string]
   :document/sentiment [:enum "positive" "negative" "neutral"]
   :document/word-count [:int {:desc "Number of words"}]})

;; Define schemas for domain events used by this example
(defschemas domain-events
  {:document/processed
   [:map
    [:text :string]
    [:document-id :uuid]]
    
   :document/analyzed
   [:map
    [:analysis :any]
    [:document-id :uuid]]
    
   :analysis/completed
   [:map
    [:summary :any]
    [:document-id :uuid]]})

;; =============================================================================
;; DSPy Models and Signatures
;; =============================================================================

;; Define a Pydantic model for structured document summary output
(defmodel DocumentSummary
  {:title (:document/title schemas)
   :key_points (:document/key-points schemas)
   :sentiment (:document/sentiment schemas)
   :word_count (:document/word-count schemas)})

;; Define DSPy signatures for different analysis tasks
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

;; =============================================================================
;; Custom Behavior Tree Actions using DSPy
;; =============================================================================

(defmethod execute-action :create-summary [_ context]
  (try
    (let [blackboard (:blackboard context)
          document (bt/get-value blackboard :document)
          title (bt/get-value blackboard :title)
          key-points (bt/get-value blackboard :key_points)
          sentiment (bt/get-value blackboard :sentiment)]
      (if (and document title key-points sentiment)
        (let [word-count (count (str/split document #"\s+"))
              summary-data {:title title
                            :key_points key-points
                            :sentiment sentiment
                            :word_count word-count}
              ;; Validate the summary
              _ (clj-dspy/validate DocumentSummary summary-data)]
          (bt/set-value blackboard :final-summary summary-data)
          (println "✓ Created validated summary")
          success)
        (do
          (println "✗ Missing required data for summary creation")
          failure)))
    (catch Exception e
      (println "✗ Error creating summary:" (.getMessage e))
      failure)))


;; =============================================================================
;; Behavior Tree Configuration
;; =============================================================================

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
      {:signature #'ExtractKeyPoints
       :operation :predict
       :log-prefix "Extracting key points"}
      :dspy]]
    [:retry {:max-retries 3}
     [:action
      {:signature #'AnalyzeSentiment
       :operation :predict
       :log-prefix "Analyzing sentiment"}
      :dspy]]]

   ;; 3. Generate title after key points are extracted
   [:sequence
    [:condition
     {:key :key_points
      :schema :document/key-points}
     :has-value]
    [:retry {:max-retries 3}
     [:action
      {:signature #'GenerateTitle
       :operation :predict
       :log-prefix "Generating title"}
      :dspy]]]

   ;; 4. Create final summary with schema validation
   [:sequence
    [:condition
     {:key :title
      :schema :document/title}
     :has-value]
    [:condition
     {:key :sentiment
      :schema :document/sentiment}
     :has-value]
    [:action :create-summary]]])

;; =============================================================================
;; Main Function
;; =============================================================================

(defmulti apply-document-event
  "Apply a document-related event to the blackboard state."
  (fn [_state event]
    (:event/type event)))

(defmethod apply-document-event :document/processed
  [state event]
  ;; Event body fields are directly accessible (no :event/body)
  (assoc state 
         :document (:text event)
         :document-id (:document-id event)))

(defmethod apply-document-event :document/analyzed  
  [state event]
  (assoc state
         :previous-analysis (:analysis event)
         :analyzed-at (:event/timestamp event)))

(defmethod apply-document-event :analysis/completed
  [state event]
  (assoc state
         :last-analysis (:summary event)
         :completed-at (:event/timestamp event)))

(defmethod apply-document-event :default
  [state _event]
  ;; Unknown events leave state unchanged
  state)

(defn document-analysis-read-model
  "Read model that builds document analysis state from events.
  This demonstrates how to initialize blackboard state from existing events.
  
  Takes a reducible of events (from event-store-v2/read) and returns a map."
  [events]
  ;; Use the standard event-sourcing pattern: reduce events into state
  (reduce apply-document-event {} events))

(defn analyze-document
  "Analyze a document using behavior tree orchestrated DSPy pipeline.
  Handles both document initialization in event store and analysis."
  [event-store document]
  (println "🚀 Starting document analysis with behavior tree...")
  (println "📄 Document:" (subs document 0 (min 100 (count document))) "...")
  
  ;; Step 1: Write document to event store
  (let [document-id (java.util.UUID/randomUUID)
        event (event-store/->event
               {:type :document/processed
                :tags #{[:document document-id]}
                :body {:text document
                       :document-id document-id}})]
    (event-store/append event-store {:events [event]})
    (println "✓ Document initialized in event store with ID:" document-id)
    
    ;; Step 2: Execute behavior tree with pure event-sourced approach
    (let [{:keys [result blackboard]} (bt/execute
                                        event-store
                                        document-analysis-tree
                                        :read-model-fn document-analysis-read-model
                                        :domain-event-config [{:types #{:document/processed :document/analyzed}
                                                               :tags #{[:document document-id]}}
                                                              {:types #{:analysis/completed}
                                                               :tags #{[:document document-id]}}])]
      (println "🏁 Analysis complete with result:" result)

      ;; Return the final summary if successful
      (if (= result success)
        (bt/get-value blackboard :final-summary)
        (do
          (println "❌ Analysis failed")
          nil)))))

;; =============================================================================
;; Example Usage
;; =============================================================================

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
programs built with behavior trees. We then show how Dendron integrates language models
into a behavior tree framework, and give three case studies demonstrating how several
natural language tasks can be easily developed using the tools provided by Dendron. We
conclude by reviewing opportunities for extending the behavior tree framework to build
more capable intelligent agents.")

(def lm (dspy/LM "openai/gpt-4o-mini"))

;; =============================================================================
;; To run the example:
;; =============================================================================
(comment
  ;;1. Configure your LLM (you'll need API keys) 
  (dspy/configure :lm lm)

  ;;2. Start the event store (in-memory for this example)
  (def event-store (event-store/start {:conn {:type :in-memory}}))

  ;;3. Run the complete example
  (analyze-document event-store sample-doc)

  ""
  )
