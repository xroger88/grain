(ns dspy-customer-service-bt
  (:require [libpython-clj2.python :as py]
            [ai.obney.grain.behavior-tree.interface :as bt]
            [ai.obney.grain.behavior-tree.core.builder :as builder]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [ai.obney.grain.event-store-v2.core.in-memory]
            [clojure.string :as str]))

;; =============================================================================
;; SETUP DSPY AND PYTHON ENVIRONMENT
;; =============================================================================

(defn setup-dspy []
  (py/initialize!)
  (py/run-simple-string "
import dspy
import os

# Set OpenAI API key if not already set
if not os.getenv('OPENAI_API_KEY'):
    os.environ['OPENAI_API_KEY'] = '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'

# Configure the language model
lm = dspy.LM('openai/gpt-4o-mini')
dspy.configure(lm=lm)

# Knowledge base - simplified customer service data
knowledge_base = {
    'billing': {
        'late_fees': 'Late fees are $25 and can be waived for first-time occurrences.',
        'payment_methods': 'We accept credit cards, bank transfers, and PayPal.',
        'billing_cycle': 'Billing cycles are monthly on the date you signed up.',
        'disputes': 'Billing disputes must be submitted within 60 days of the charge.'
    },
    'technical': {
        'password_reset': 'To reset your password, click \"Forgot Password\" on the login page.',
        'connectivity': 'Check your internet connection and try restarting your router.',
        'app_crashes': 'Try updating the app to the latest version or reinstalling.',
        'sync_issues': 'Ensure you are logged into the same account on all devices.'
    },
    'account': {
        'account_closure': 'To close your account, contact customer service directly.',
        'data_export': 'You can export your data from Account Settings > Data Export.',
        'privacy_settings': 'Privacy settings can be managed in Account Settings > Privacy.',
        'two_factor': 'Enable two-factor authentication in Account Settings > Security.'
    },
    'orders': {
        'tracking': 'Track your order using the tracking number sent via email.',
        'returns': 'Returns are accepted within 30 days of purchase.',
        'exchanges': 'Exchanges must be initiated within 14 days of delivery.',
        'shipping': 'Standard shipping takes 5-7 business days, express takes 2-3 days.'
    }
}

def search_knowledge_base(query, category=None):
    \"\"\"Search the knowledge base for relevant information\"\"\"
    results = []
    categories_to_search = [category] if category else knowledge_base.keys()
    
    for cat in categories_to_search:
        if cat in knowledge_base:
            for key, value in knowledge_base[cat].items():
                if any(word.lower() in value.lower() or word.lower() in key.lower() 
                       for word in query.lower().split()):
                    results.append({
                        'category': cat,
                        'topic': key,
                        'content': value,
                        'relevance': len([w for w in query.lower().split() 
                                        if w in value.lower() or w in key.lower()])
                    })
    
    # Sort by relevance
    results.sort(key=lambda x: x['relevance'], reverse=True)
    return results[:3]  # Return top 3 results
"))

;; =============================================================================
;; DSPY SIGNATURES FOR CUSTOMER SERVICE
;; =============================================================================

(defn setup-signatures []
  (py/run-simple-string "
class ClassifyQuery(dspy.Signature):
    '''Classify a customer service query into categories and urgency'''
    query = dspy.InputField(desc='Customer service query')
    category = dspy.OutputField(desc='Primary category: billing, technical, account, orders, or general')
    urgency = dspy.OutputField(desc='Urgency level: low, medium, high, urgent')
    intent = dspy.OutputField(desc='Specific intent or action needed')

class GenerateResponse(dspy.Signature):
    '''Generate a helpful customer service response'''
    query = dspy.InputField(desc='Original customer query')
    category = dspy.InputField(desc='Query category')
    knowledge = dspy.InputField(desc='Relevant knowledge base information')
    conversation_history = dspy.InputField(desc='Previous conversation context')
    response = dspy.OutputField(desc='Helpful, professional customer service response')

class EscalationDecision(dspy.Signature):
    '''Decide if a query needs human escalation'''
    query = dspy.InputField(desc='Customer query')
    category = dspy.InputField(desc='Query category') 
    urgency = dspy.InputField(desc='Urgency level')
    previous_attempts = dspy.InputField(desc='Number of previous resolution attempts')
    needs_escalation = dspy.OutputField(desc='True if needs human escalation, False otherwise')
    reason = dspy.OutputField(desc='Reason for escalation decision')

class SentimentAnalysis(dspy.Signature):
    '''Analyze customer sentiment'''
    query = dspy.InputField(desc='Customer message')
    sentiment = dspy.OutputField(desc='Customer sentiment: positive, neutral, negative, frustrated, angry')
    confidence = dspy.OutputField(desc='Confidence level: low, medium, high')

# Initialize the modules
classify_query = dspy.ChainOfThought(ClassifyQuery)
generate_response = dspy.ChainOfThought(GenerateResponse)
escalation_decision = dspy.ChainOfThought(EscalationDecision)
sentiment_analysis = dspy.ChainOfThought(SentimentAnalysis)
"))

;; =============================================================================
;; BEHAVIOR TREE ACTIONS FOR CUSTOMER SERVICE
;; =============================================================================

(defmethod builder/execute-action :analyze-sentiment [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)]
    (try
      (let [call-fn (py/get-attr (py/import-module "__main__") "call_sentiment_analysis")
            result (call-fn query)
            sentiment (py/py.- result "sentiment")
            confidence (py/py.- result "confidence")]
        (bt/set-value blackboard :sentiment sentiment)
        (bt/set-value blackboard :sentiment-confidence confidence)
        (println (str "Sentiment: " sentiment " (confidence: " confidence ")"))
        bt/success)
      (catch Exception e
        (println "Error analyzing sentiment:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :classify-query [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)]
    (try
      (let [call-fn (py/get-attr (py/import-module "__main__") "call_classify_query")
            result (call-fn query)
            category (py/py.- result "category")
            urgency (py/py.- result "urgency")
            intent (py/py.- result "intent")]
        (bt/set-value blackboard :category category)
        (bt/set-value blackboard :urgency urgency)
        (bt/set-value blackboard :intent intent)
        (println (str "Classified as: " category " (" urgency " urgency)"))
        bt/success)
      (catch Exception e
        (println "Error classifying query:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :search-knowledge-base [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        category (bt/get-value blackboard :category)]
    (try
      (let [search-fn (py/get-attr (py/import-module "__main__") "search_knowledge_base")
            results (search-fn query category)]
        (bt/set-value blackboard :knowledge-results (py/->jvm results))
        (println (str "Found " (count (py/->jvm results)) " knowledge base entries"))
        (if (empty? (py/->jvm results))
          bt/failure
          bt/success))
      (catch Exception e
        (println "Error searching knowledge base:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :generate-response [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        category (bt/get-value blackboard :category)
        knowledge-results (bt/get-value blackboard :knowledge-results)
        conversation-history (or (bt/get-value blackboard :conversation-history) "")
        
        ;; Format knowledge for the LM
        knowledge-text (if knowledge-results
                         (str/join "\n" (map #(str "- " (get % "content")) knowledge-results))
                         "No specific knowledge found")]
    (try
      (let [call-fn (py/get-attr (py/import-module "__main__") "call_generate_response")
            result (call-fn query category knowledge-text conversation-history)
            response (py/py.- result "response")]
        (bt/set-value blackboard :response response)
        (bt/set-value blackboard :response-generated true)
        (println (str "Generated response: " (subs response 0 (min 100 (count response))) "..."))
        bt/success)
      (catch Exception e
        (println "Error generating response:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :check-escalation [_ context]
  (let [query (:query context)
        blackboard (:blackboard context)
        category (bt/get-value blackboard :category)
        urgency (bt/get-value blackboard :urgency)
        attempts (or (bt/get-value blackboard :resolution-attempts) 0)]
    (try
      (let [call-fn (py/get-attr (py/import-module "__main__") "call_escalation_decision")
            result (call-fn query category urgency attempts)
            needs-escalation (py/py.- result "needs_escalation")
            reason (py/py.- result "reason")]
        (bt/set-value blackboard :needs-escalation (= needs-escalation "True"))
        (bt/set-value blackboard :escalation-reason reason)
        (if (= needs-escalation "True")
          (do
            (println (str "Escalation needed: " reason))
            bt/success)
          (do
            (println "No escalation needed")
            bt/failure)))
      (catch Exception e
        (println "Error checking escalation:" (.getMessage e))
        bt/failure))))

(defmethod builder/execute-action :escalate-to-human [_ context]
  (let [blackboard (:blackboard context)
        reason (bt/get-value blackboard :escalation-reason)]
    (bt/set-value blackboard :escalated true)
    (bt/set-value blackboard :response 
                  (str "I'm transferring you to a human agent who can better assist you. "
                       "Reason: " reason ". Please hold while I connect you."))
    (println "ESCALATED TO HUMAN:" reason)
    bt/success))

(defmethod builder/execute-action :provide-fallback-response [_ context]
  (let [blackboard (:blackboard context)
        category (bt/get-value blackboard :category)]
    (bt/set-value blackboard :response
                  (str "I apologize, but I'm having trouble finding specific information "
                       "for your " category " question. Let me connect you with a specialist "
                       "who can provide more detailed assistance."))
    (println "Providing fallback response")
    bt/success))

(defmethod builder/execute-action :update-conversation-history [_ context]
  (let [blackboard (:blackboard context)
        query (:query context)
        response (bt/get-value blackboard :response)
        current-history (or (bt/get-value blackboard :conversation-history) "")]
    (bt/set-value blackboard :conversation-history
                  (str current-history 
                       "\nCustomer: " query
                       "\nAgent: " response))
    (bt/set-value blackboard :resolution-attempts 
                  (inc (or (bt/get-value blackboard :resolution-attempts) 0)))
    bt/success))

(defmethod builder/execute-action :log-interaction [_ context]
  (let [blackboard (:blackboard context)
        query (:query context)
        category (bt/get-value blackboard :category)
        urgency (bt/get-value blackboard :urgency)
        sentiment (bt/get-value blackboard :sentiment)
        escalated (bt/get-value blackboard :escalated)]
    (println "\n=== INTERACTION LOG ===")
    (println "Query:" query)
    (println "Category:" category)
    (println "Urgency:" urgency) 
    (println "Sentiment:" sentiment)
    (println "Escalated:" (if escalated "YES" "NO"))
    (println "Response:" (bt/get-value blackboard :response))
    (println "=====================\n")
    bt/success))

;; =============================================================================
;; BEHAVIOR TREE CONDITIONS
;; =============================================================================

(defmethod builder/evaluate-condition :high-urgency? [_ context]
  (let [blackboard (:blackboard context)
        urgency (bt/get-value blackboard :urgency)]
    (contains? #{"high" "urgent"} urgency)))

(defmethod builder/evaluate-condition :negative-sentiment? [_ context]
  (let [blackboard (:blackboard context)
        sentiment (bt/get-value blackboard :sentiment)]
    (contains? #{"negative" "frustrated" "angry"} sentiment)))

(defmethod builder/evaluate-condition :knowledge-found? [_ context]
  (let [blackboard (:blackboard context)
        results (bt/get-value blackboard :knowledge-results)]
    (and results (seq results))))

(defmethod builder/evaluate-condition :response-generated? [_ context]
  (let [blackboard (:blackboard context)]
    (boolean (bt/get-value blackboard :response-generated))))

(defmethod builder/evaluate-condition :needs-escalation? [_ context]
  (let [blackboard (:blackboard context)]
    (boolean (bt/get-value blackboard :needs-escalation))))

;; =============================================================================
;; CUSTOMER SERVICE BEHAVIOR TREE
;; =============================================================================

(def customer-service-agent
  "Advanced customer service agent using behavior trees"
  [:sequence
    ;; 1. Initial Analysis Phase
    [:parallel {:success-threshold 2}
      [:action :analyze-sentiment]
      [:action :classify-query]]
    
    ;; 2. Knowledge Retrieval
    [:action :search-knowledge-base]
    
    ;; 3. Response Strategy (prioritized by urgency and sentiment)
    [:fallback
      ;; High priority: Negative sentiment + High urgency
      [:sequence
        [:condition :negative-sentiment?]
        [:condition :high-urgency?]
        [:action :check-escalation]
        [:fallback
          [:sequence
            [:condition :needs-escalation?]
            [:action :escalate-to-human]]
          [:sequence
            [:condition :knowledge-found?]
            [:action :generate-response]]
          [:action :provide-fallback-response]]]
      
      ;; Medium priority: High urgency (any sentiment)
      [:sequence
        [:condition :high-urgency?]
        [:fallback
          [:sequence
            [:condition :knowledge-found?]
            [:action :generate-response]]
          [:sequence
            [:action :check-escalation]
            [:condition :needs-escalation?]
            [:action :escalate-to-human]]
          [:action :provide-fallback-response]]]
      
      ;; Normal priority: Standard response flow
      [:fallback
        [:sequence
          [:condition :knowledge-found?]
          [:action :generate-response]]
        [:sequence
          [:action :check-escalation]
          [:condition :needs-escalation?]
          [:action :escalate-to-human]]
        [:action :provide-fallback-response]]]
    
    ;; 4. Finalization
    [:parallel {:success-threshold 2}
      [:action :update-conversation-history]
      [:action :log-interaction]]])

;; =============================================================================
;; USAGE FUNCTIONS
;; =============================================================================

(defn create-customer-service-system
  "Initialize the complete customer service system"
  []
  (setup-dspy)
  (setup-signatures)
  (let [event-store (event-store/start {:conn {:type :in-memory}})
        blackboard (bt/create-blackboard event-store)]
    {:tree (bt/build-behavior-tree customer-service-agent)
     :blackboard blackboard}))

(defn handle-customer-query
  "Process a customer query through the behavior tree"
  [system query]
  (let [{:keys [tree blackboard]} system
        context {:query query :blackboard blackboard}]
    (bt/run-tree tree context)
    {:response (bt/get-value blackboard :response)
     :category (bt/get-value blackboard :category)
     :urgency (bt/get-value blackboard :urgency)
     :sentiment (bt/get-value blackboard :sentiment)
     :escalated (boolean (bt/get-value blackboard :escalated))}))

;; =============================================================================
;; EXAMPLE USAGE
;; =============================================================================

(comment
  ;; Initialize the system
  (def cs-system (create-customer-service-system))
  
  ;; Test various customer queries
  
  ;; 1. Billing question
  (handle-customer-query cs-system 
    "I was charged a late fee but I paid on time. Can you help?")
  
  ;; 2. Technical issue  
  (handle-customer-query cs-system
    "My app keeps crashing and I can't access my account!")
  
  ;; 3. Urgent complaint
  (handle-customer-query cs-system
    "This is ridiculous! I've been trying to get help for hours and nobody is responding!")
  
  ;; 4. Simple question
  (handle-customer-query cs-system
    "How do I track my order?")
  
  ;; 5. Complex account issue
  (handle-customer-query cs-system
    "I need to close my account but first export all my data. Is there a way to do both?")
  )

;; =============================================================================
;; ADVANTAGES OF BEHAVIOR TREE APPROACH OVER REACT
;; =============================================================================

;; ✅ BETTER THAN REACT BECAUSE:
;;
;; 1. **Explicit Priority Handling**
;;    - BT explicitly handles urgency and sentiment combinations
;;    - ReAct would need complex prompting for this logic
;;
;; 2. **Parallel Processing** 
;;    - Sentiment analysis and classification happen simultaneously
;;    - ReAct is inherently sequential
;;
;; 3. **Deterministic Escalation Logic**
;;    - Clear, testable conditions for when to escalate
;;    - ReAct escalation depends on LM interpretation
;;
;; 4. **Graceful Degradation**
;;    - Fallback responses are built into the tree structure  
;;    - ReAct might get stuck or give inconsistent fallbacks
;;
;; 5. **State Persistence**
;;    - Conversation history and context preserved across interactions
;;    - ReAct would need external memory management
;;
;; 6. **Composable and Extensible**
;;    - Easy to add new conditions, actions, or modify flow
;;    - ReAct requires prompt engineering for changes
;;
;; 7. **Performance Optimizations**
;;    - Only necessary LM calls are made based on tree structure
;;    - ReAct might make redundant or unnecessary calls
;;
;; 8. **Debugging and Monitoring**
;;    - Easy to trace execution path and see decision points
;;    - ReAct reasoning can be opaque
;;
;; 9. **Multi-Modal Integration**
;;    - Can easily integrate with non-LM systems (databases, APIs)
;;    - ReAct is primarily text-based reasoning