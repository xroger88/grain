(ns bt-dspy-clean-2
  (:require [ai.obney.grain.behavior-tree-v2.interface :as bt2]
            [ai.obney.grain.behavior-tree-v2.interface.protocol :as btp]
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
  (let [lt-memory-state (btp/latest lt-memory)]
    (m/validate
     schema
     (if path
       (get-in lt-memory-state path)
       lt-memory-state))))

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
      (let [inputs (reduce (fn [acc key]
                             (let [value (get @st-memory key)]
                               (if value
                                 (assoc acc key value)
                                 (reduced nil))))
                           {} input-keys)]
        (if inputs
          (let [result (execute-dspy-operation operation signature context inputs)]
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
  {:inputs {:key_points (:document/key-points schemas)}
   :outputs {:title (:document/title schemas)}})

;; ## BT Actions

(defn calculate-word-count
  [{:keys [st-memory]}]
  (let [text (:document @st-memory)
        word-count (count (str/split text #"\s+"))]
    (swap! st-memory assoc :word_count word-count)
    (println "✓ Calculated word count:" word-count)
    bt2/success))

(defn create-final-summary
  [{:keys [st-memory]}]
  (let [{:keys [title key_points sentiment word_count]} @st-memory]
    (swap! st-memory assoc :final_summary
           {:title title
            :key_points key_points
            :sentiment sentiment
            :word_count word_count}))
  bt2/success)

;; ## Behavior Tree

(def document-analysis-tree
  "Behavior tree for document analysis and summarization using DSPy"
  [:sequence
   ;; 1. Validate input document exists with schema validation
   [:condition
    {:path [:document]
     :schema :document/text}
    st-memory-has-value?]

   ;; 2. Extract key information in parallel using declarative DSPy actions
   [:parallel
    [:action
     {:id :extract-key-points
      :signature #'ExtractKeyPoints
      :operation :chain-of-thought}
     dspy]
    
    [:action
     {:id :analyze-sentiment
      :signature #'AnalyzeSentiment
      :operation :chain-of-thought}
     dspy]]

   ;; 3. Generate title after key points are extracted
   [:sequence
    [:condition
     {:path [:key_points]
      :schema :document/key-points}
     st-memory-has-value?]
    [:action
     {:id :generate-title
      :signature #'GenerateTitle
      :operation :chain-of-thought}
     dspy]]

   ;; 4. Calculate word count deterministically
   [:action calculate-word-count]

   ;; 5. Create final summary using DSPy
   [:sequence
    [:condition
     {:schema [:map
               [:title :document/title]
               [:sentiment :document/sentiment]
               [:word_count :document/word-count]
               [:key_points :document/key-points]]}
     st-memory-has-value?]
    
    [:action create-final-summary]]

   [:condition
    {:path [:final_summary]
     :schema :document/summary}
    st-memory-has-value?]])

;; ## Entrypoint

(defn analyze-document
  "Analyze a document using behavior tree orchestrated DSPy pipeline."
  [event-store document]
  (println "🚀 Starting document analysis with behavior tree...")
  (println "📄 Document:" (subs document 0 (min 100 (count document))) "...")

  ;; Execute with initial blackboard state (single event internally)
  (let [bt (bt2/build
            document-analysis-tree
            {:event-store event-store
             :st-memory {:document document}})
        result (bt2/run bt)]
    (println result)))

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

  (into [] (event-store/read event-store {}))


  (require '[clojure.data.json :as json])

  (json/write-str
   document-analysis-tree)



 
  


  ""
  )

