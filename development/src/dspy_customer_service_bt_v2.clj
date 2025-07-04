(ns dspy-customer-service-bt-v2
  (:require [libpython-clj2.python :as py]
            [libpython-clj2.require :refer [require-python]]
            [ai.obney.grain.behavior-tree.interface :as bt]
            [ai.obney.grain.behavior-tree.core.builder :as builder]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [clojure.string :as str]))

;; =============================================================================
;; DIRECT DSPY INTEROP  
;; =============================================================================

(require-python '__main__)

;; Direct DSPy function calls from main namespace
(defn analyze-sentiment [query]
  (let [main-module (py/import-module "__main__")]
    (py/call-attr main-module "call_sentiment_analysis" query)))

(defn classify-query [query]  
  (let [main-module (py/import-module "__main__")]
    (py/call-attr main-module "call_classify_query" query)))

(defn generate-response [query category knowledge history]
  (let [main-module (py/import-module "__main__")]
    (py/call-attr main-module "call_generate_response" query category knowledge history)))

(defn check-escalation [query category urgency attempts]
  (let [main-module (py/import-module "__main__")]
    (py/call-attr main-module "call_escalation_decision" query category urgency attempts)))

(defn search-knowledge [query category]
  (let [main-module (py/import-module "__main__")]
    (py/call-attr main-module "search_knowledge_base" query category)))

;; =============================================================================
;; OPTIMIZED BEHAVIOR TREE ACTIONS WITH MINIMAL BLACKBOARD WRITES
;; =============================================================================

(defmethod builder/execute-action :analyze-sentiment-v2 [_ context]
  "Analyze customer sentiment using DSPy"
  (let [query (:query context)
        blackboard (:blackboard context)
        start-time (System/currentTimeMillis)]
    (try
      (let [result (analyze-sentiment query)
            sentiment (py/get-attr result "sentiment")
            confidence (py/get-attr result "confidence")
            end-time (System/currentTimeMillis)]
        (bt/set-value blackboard :sentiment sentiment)
        (bt/set-value blackboard :confidence confidence)
        (println (str "Sentiment: " sentiment " (confidence: " confidence ") [" (- end-time start-time) "ms]"))
        bt/success)
      (catch Exception e
        (println "Error in sentiment analysis:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :classify-query-v2 [_ context]
  "Classify customer query using DSPy"
  (let [query (:query context)
        blackboard (:blackboard context)
        start-time (System/currentTimeMillis)]
    (try
      (let [result (classify-query query)
            category (py/get-attr result "category")
            urgency (py/get-attr result "urgency")
            intent (py/get-attr result "intent")
            end-time (System/currentTimeMillis)]
        (bt/set-value blackboard :category category)
        (bt/set-value blackboard :urgency urgency)
        (bt/set-value blackboard :intent intent)
        (println (str "Classified as: " category " (" urgency " urgency) [" (- end-time start-time) "ms]"))
        bt/success)
      (catch Exception e
        (println "Error in classification:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :search-knowledge-base-v2 [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        category (:category all-data)]
    (try
      (let [results (py/->jvm (search-knowledge query category))]
        (bt/set-value blackboard :knowledge-results results)
        (println (str "Found " (count results) " knowledge base entries"))
        (if (empty? results) bt/failure bt/success))
      (catch Exception e
        (println "Error searching knowledge base:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :generate-response-v2 [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        knowledge-results (:knowledge-results all-data)
        category (:category all-data)
        conversation-history (or (:conversation-history all-data) "")
        
        knowledge-text (if (seq knowledge-results)
                         (str/join "\\n" (map #(str "- " (get % "content")) knowledge-results))
                         "No specific knowledge found")]
    (try
      (let [result (generate-response query category knowledge-text conversation-history)
            response (py/get-attr result "response")]
        (bt/set-value blackboard :response response)
        (println (str "Generated response: " (subs response 0 (min 100 (count response))) "..."))
        bt/success)
      (catch Exception e
        (println "Error generating response:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :check-escalation-v2 [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        category (:category all-data)
        urgency (:urgency all-data)
        attempts (or (:resolution-attempts all-data) 0)]
    (try
      (let [result (check-escalation query category urgency attempts)
            needs-escalation (py/get-attr result "needs_escalation")
            reason (py/get-attr result "reason")]
        (bt/set-value blackboard :escalation-decision
                      {:needs-escalation (= needs-escalation "True")
                       :reason reason})
        (if (= needs-escalation "True")
          (do (println (str "Escalation needed: " reason)) bt/success)
          (do (println "No escalation needed") bt/failure)))
      (catch Exception e
        (println "Error checking escalation:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :escalate-to-human-v2 [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        escalation-decision (:escalation-decision all-data)
        reason (:reason escalation-decision)]
    (bt/set-value blackboard :final-response
                  {:response (str "I'm transferring you to a human agent who can better assist you. "
                                  "Reason: " reason ". Please hold while I connect you.")
                   :escalated true})
    (println "ESCALATED TO HUMAN:" reason)
    bt/success))

(defmethod builder/execute-action :provide-final-response-v2 [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        response (:response all-data)]
    (bt/set-value blackboard :final-response
                  {:response response
                   :escalated false})
    (println "Providing standard response")
    bt/success))

(defmethod builder/execute-action :finalize-interaction-v2 [_ context]
  "Single action that handles conversation history update and logging"
  (let [blackboard (:blackboard context)
        query (:query context)
        all-data (bt/get-all blackboard)
        analysis (:analysis-results all-data)
        final-response (:final-response all-data)
        current-history (or (:conversation-history all-data) "")]
    
    ;; Single blackboard write with all finalization data
    (bt/set-value blackboard :interaction-complete
                  {:conversation-history (str current-history 
                                              "\\nCustomer: " query
                                              "\\nAgent: " (:response final-response))
                   :resolution-attempts (inc (or (:resolution-attempts all-data) 0))
                   :final-result final-response})
    
    ;; Log the interaction
    (println "\\n=== INTERACTION LOG ===")
    (println "Query:" query)
    (println "Category:" (:category analysis))
    (println "Urgency:" (:urgency analysis))
    (println "Sentiment:" (:sentiment analysis))
    (println "Escalated:" (if (:escalated final-response) "YES" "NO"))
    (println "Response:" (:response final-response))
    (println "=====================\\n")
    bt/success))

;; =============================================================================
;; BEHAVIOR TREE CONDITIONS (using optimized data access)
;; =============================================================================

(defmethod builder/evaluate-condition :high-urgency-v2? [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        analysis (:analysis-results all-data)]
    (contains? #{"high" "urgent"} (:urgency analysis))))

(defmethod builder/evaluate-condition :negative-sentiment-v2? [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        analysis (:analysis-results all-data)]
    (contains? #{"negative" "frustrated" "angry"} (:sentiment analysis))))

(defmethod builder/evaluate-condition :knowledge-found-v2? [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        results (:knowledge-results all-data)]
    (and results (seq results))))

(defmethod builder/evaluate-condition :needs-escalation-v2? [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)
        escalation-decision (:escalation-decision all-data)]
    (:needs-escalation escalation-decision)))

(defmethod builder/evaluate-condition :has-response-v2? [_ context]
  (let [blackboard (:blackboard context)
        all-data (bt/get-all blackboard)]
    (boolean (:response all-data))))

;; =============================================================================
;; OPTIMIZED CUSTOMER SERVICE BEHAVIOR TREE
;; =============================================================================

(def customer-service-agent-v2
  "Optimized customer service agent with minimal blackboard writes"
  [:sequence
    ;; 1. Analysis Phase (parallel processing for demo)
    [:parallel
      [:action :analyze-sentiment-v2]
      [:action :classify-query-v2]]
    
    ;; 2. Knowledge Retrieval
    [:action :search-knowledge-base-v2]
    
    ;; 3. Response Strategy (prioritized by urgency and sentiment)
    [:fallback
      ;; High priority: Negative sentiment + High urgency
      [:sequence
        [:condition :negative-sentiment-v2?]
        [:condition :high-urgency-v2?]
        [:action :check-escalation-v2]
        [:fallback
          [:sequence
            [:condition :needs-escalation-v2?]
            [:action :escalate-to-human-v2]]
          [:sequence
            [:condition :knowledge-found-v2?]
            [:action :generate-response-v2]
            [:action :provide-final-response-v2]]
          [:action :provide-final-response-v2]]]
      
      ;; Medium priority: High urgency (any sentiment)
      [:sequence
        [:condition :high-urgency-v2?]
        [:fallback
          [:sequence
            [:condition :knowledge-found-v2?]
            [:action :generate-response-v2]
            [:action :provide-final-response-v2]]
          [:sequence
            [:action :check-escalation-v2]
            [:condition :needs-escalation-v2?]
            [:action :escalate-to-human-v2]]
          [:action :provide-final-response-v2]]]
      
      ;; Normal priority: Standard response flow
      [:fallback
        [:sequence
          [:condition :knowledge-found-v2?]
          [:action :generate-response-v2]
          [:action :provide-final-response-v2]]
        [:sequence
          [:action :check-escalation-v2]
          [:condition :needs-escalation-v2?]
          [:action :escalate-to-human-v2]]
        [:action :provide-final-response-v2]]]
    
    ;; 4. Finalization (single blackboard write)
    [:action :finalize-interaction-v2]])

;; =============================================================================
;; USAGE FUNCTIONS
;; =============================================================================

(defn create-customer-service-system-v2
  "Initialize the optimized customer service system"
  []
  (let [event-store (event-store/start {:conn {:type :in-memory}})
        blackboard (bt/create-blackboard event-store)]
    {:tree (bt/build-behavior-tree customer-service-agent-v2)
     :blackboard blackboard}))

(defn handle-customer-query-v2
  "Process a customer query through the optimized behavior tree"
  [system query]
  (let [{:keys [tree blackboard]} system
        context {:query query :blackboard blackboard}]
    (bt/run-tree tree context)
    (let [all-data (bt/get-all blackboard)
          final-response (:final-response all-data)]
      {:response (:response final-response)
       :category (:category all-data)
       :urgency (:urgency all-data)
       :sentiment (:sentiment all-data)
       :escalated (:escalated final-response)})))

;; =============================================================================
;; COMPARISON DEMO
;; =============================================================================

(defn compare-versions
  "Compare event generation between v1 and v2"
  [query]
  (println "\\n=== COMPARISON: Event Generation ===")
  (let [es2 (event-store/start {:conn {:type :in-memory}})
        bb2 (bt/create-blackboard es2)
        
        ;; V1 system (from original) - requires separate loading
        ;; tree1 (bt/build-behavior-tree (var-get #'dspy-customer-service-bt/customer-service-agent))
        ;; system1 {:tree tree1 :blackboard bb1}
        
        ;; V2 system (optimized)
        tree2 (bt/build-behavior-tree customer-service-agent-v2)
        system2 {:tree tree2 :blackboard bb2}]
    
    (println "Running V1 (original)...")
    (println "V1 Events generated: [comparison requires loading v1 namespace]")
    
    (println "\\nRunning V2 (optimized)...")
    (let [result2 (handle-customer-query-v2 system2 query)
          events2 (reduce conj [] (event-store/read es2 {}))]
      (println (str "V2 Events generated: " (count events2)))
      (println (str "V2 Result: " result2)))
    
    (println "=== END COMPARISON ===\\n")))