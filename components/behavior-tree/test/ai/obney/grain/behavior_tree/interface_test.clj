(ns ai.obney.grain.behavior-tree.interface-test
  (:require [clojure.test :as test :refer :all]
            [ai.obney.grain.behavior-tree.interface :as bt]
            [ai.obney.grain.behavior-tree.core.blackboard :as bb]
            [ai.obney.grain.behavior-tree.interface.protocols :refer [success failure]]
            [ai.obney.grain.event-store-v2.interface :as event-store]
            [ai.obney.grain.event-store-v2.core.in-memory]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

;; =============================================================================
;; Test Setup
;; =============================================================================

;; Define schemas for test events
(defschemas test-events
  {:test/event
   [:map
    [:data :string]]
   
   :document/created
   [:map
    [:text :string]
    [:document-id :uuid]]
    
   :analysis/completed
   [:map
    [:result :string]
    [:document-id :uuid]]})

(defn create-test-event-store []
  (event-store/start {:conn {:type :in-memory}}))

(defn create-test-event [type body & {:keys [tags]}]
  (event-store/->event 
    {:type type
     :tags (or tags #{})
     :body body}))

;; =============================================================================
;; Blackboard Core Tests
;; =============================================================================

(deftest test-event-store-basic-operations
  (testing "Basic event store operations work"
    (let [event-store (create-test-event-store)
          test-id (uuid/v7)
          test-event (create-test-event :test/event {:data "test"} :tags #{[:test test-id]})]
      
      (event-store/append event-store {:events [test-event]})
      
      (let [all-events (into [] (event-store/read event-store {}))
            test-events (filter #(= :test/event (:event/type %)) all-events)]
        (is (= 1 (count test-events)))
        (is (= :test/event (:event/type (first test-events))))))))

(deftest test-blackboard-basic-operations
  (testing "Basic blackboard get/set operations"
    (let [event-store (create-test-event-store)
          blackboard (bb/create-event-sourced-blackboard event-store)]
      
      ;; Test initial empty state
      (is (nil? (bt/get-value blackboard :key1)))
      (is (= {} (bt/get-all blackboard)))
      
      ;; Test set and get
      (bt/set-value blackboard :key1 "value1")
      (is (= "value1" (bt/get-value blackboard :key1)))
      
      ;; Test multiple values
      (bt/set-value blackboard :key2 42)
      (is (= "value1" (bt/get-value blackboard :key1)))
      (is (= 42 (bt/get-value blackboard :key2)))
      (is (= {:key1 "value1" :key2 42} (bt/get-all blackboard)))
      
      ;; Test overwrite
      (bt/set-value blackboard :key1 "new-value")
      (is (= "new-value" (bt/get-value blackboard :key1)))
      (is (= {:key1 "new-value" :key2 42} (bt/get-all blackboard))))))

(deftest test-blackboard-persistence
  (testing "Blackboard state persisted to event store"
    (let [event-store (create-test-event-store)
          bb-id (uuid/v7)
          blackboard1 (bb/create-event-store-blackboard event-store bb-id)
          blackboard2 (bb/create-event-store-blackboard event-store bb-id)]
      
      ;; Set values in first blackboard
      (bt/set-value blackboard1 :key1 "value1")
      (bt/set-value blackboard1 :key2 42)
      
      ;; Second blackboard with same ID should see the values
      (is (= "value1" (bt/get-value blackboard2 :key1)))
      (is (= 42 (bt/get-value blackboard2 :key2)))
      (is (= {:key1 "value1" :key2 42} (bt/get-all blackboard2))))))

(deftest test-blackboard-isolation
  (testing "Different blackboards are isolated"
    (let [event-store (create-test-event-store)
          blackboard1 (bb/create-event-sourced-blackboard event-store)
          blackboard2 (bb/create-event-sourced-blackboard event-store)]
      
      ;; Set values in first blackboard
      (bt/set-value blackboard1 :key1 "value1")
      
      ;; Second blackboard should not see the values
      (is (nil? (bt/get-value blackboard2 :key1)))
      (is (= {} (bt/get-all blackboard2)))
      
      ;; Set different value in second blackboard
      (bt/set-value blackboard2 :key1 "different-value")
      
      ;; Values should remain separate
      (is (= "value1" (bt/get-value blackboard1 :key1)))
      (is (= "different-value" (bt/get-value blackboard2 :key1))))))

;; =============================================================================
;; Read Model Tests
;; =============================================================================

(defmulti test-read-model-event (fn [_state event] (:event/type event)))

(defmethod test-read-model-event :document/created
  [state event]
  (assoc state :document (:text event) :document-id (:document-id event)))

(defmethod test-read-model-event :analysis/completed  
  [state event]
  (assoc state :analysis-result (:result event)))

(defmethod test-read-model-event :default [state _event] state)

(defn test-read-model [events]
  (reduce test-read-model-event {} events))

(deftest test-blackboard-with-read-model
  (testing "Blackboard with read model processes domain events"
    (let [event-store (create-test-event-store)
          doc-id (uuid/v7)]
      
      ;; Write domain events to event store
      (let [events [(create-test-event :document/created 
                      {:text "Test document" :document-id doc-id}
                      :tags #{[:document doc-id]})
                    (create-test-event :analysis/completed
                      {:result "positive" :document-id doc-id}
                      :tags #{[:document doc-id]})]]
        (event-store/append event-store {:events events}))
      
      ;; Create blackboard with read model
      (let [blackboard (bb/create-event-sourced-blackboard 
                         event-store
                         :read-model-fn test-read-model
                         :domain-event-config [{:types #{:document/created :analysis/completed}
                                                :tags #{[:document doc-id]}}])]
        
        ;; Should see state from domain events
        (is (= "Test document" (bt/get-value blackboard :document)))
        (is (= doc-id (bt/get-value blackboard :document-id)))
        (is (= "positive" (bt/get-value blackboard :analysis-result)))
        
        ;; Blackboard mutations should work on top of read model
        (bt/set-value blackboard :custom-key "custom-value")
        (is (= "custom-value" (bt/get-value blackboard :custom-key)))
        (is (= "Test document" (bt/get-value blackboard :document))) ; Read model state preserved
        ))))

(deftest test-blackboard-incremental-domain-updates
  (testing "Blackboard sees new domain events incrementally"
    (let [event-store (create-test-event-store)
          doc-id (uuid/v7)]
      
      ;; Initial domain event
      (event-store/append event-store 
        {:events [(create-test-event :document/created 
                    {:text "Initial document" :document-id doc-id}
                    :tags #{[:document doc-id]})]})
      
      ;; Create blackboard
      (let [blackboard (bb/create-event-sourced-blackboard 
                         event-store
                         :read-model-fn test-read-model
                         :domain-event-config [{:types #{:document/created :analysis/completed}
                                                :tags #{[:document doc-id]}}])]
        
        ;; Initial state
        (is (= "Initial document" (bt/get-value blackboard :document)))
        (is (nil? (bt/get-value blackboard :analysis-result)))
        
        ;; Add new domain event
        (event-store/append event-store 
          {:events [(create-test-event :analysis/completed
                      {:result "negative" :document-id doc-id}
                      :tags #{[:document doc-id]})]})
        
        ;; Should see updated state on next access
        (is (= "Initial document" (bt/get-value blackboard :document)))
        (is (= "negative" (bt/get-value blackboard :analysis-result)))))))

(deftest test-blackboard-tag-filtering
  (testing "Blackboard only processes events with matching tags"
    (let [event-store (create-test-event-store)
          doc-id-1 (uuid/v7)
          doc-id-2 (uuid/v7)]
      
      ;; Write events for two different documents
      (event-store/append event-store 
        {:events [(create-test-event :document/created 
                    {:text "Document 1" :document-id doc-id-1}
                    :tags #{[:document doc-id-1]})
                  (create-test-event :document/created
                    {:text "Document 2" :document-id doc-id-2}
                    :tags #{[:document doc-id-2]})]})
      
      ;; Create blackboard that only sees document 1
      (let [blackboard (bb/create-event-sourced-blackboard 
                         event-store
                         :read-model-fn test-read-model
                         :domain-event-config [{:types #{:document/created}
                                                :tags #{[:document doc-id-1]}}])]
        
        ;; Should only see document 1
        (is (= "Document 1" (bt/get-value blackboard :document)))
        (is (= doc-id-1 (bt/get-value blackboard :document-id)))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-bt-execute-basic
  (testing "Basic behavior tree execution"
    (let [event-store (create-test-event-store)
          config [:sequence
                  [:action :succeed]]
          result (bt/execute event-store config)]
      
      (is (= success (:result result)))
      (is (some? (:blackboard result))))))

(deftest test-bt-execute-with-blackboard-state
  (testing "Behavior tree execution with blackboard state manipulation"
    (let [event-store (create-test-event-store)
          config [:sequence
                  [:action {:key :test-key :value "test-value"} :set-blackboard-value]
                  [:action :succeed]]
          {:keys [result blackboard]} (bt/execute event-store config)]
      
      (is (= success result))
      (is (= "test-value" (bt/get-value blackboard :test-key))))))

(deftest test-bt-execute-with-read-model
  (testing "Behavior tree execution with read model initialization"
    (let [event-store (create-test-event-store)
          doc-id (uuid/v7)]
      
      ;; Setup domain events
      (event-store/append event-store 
        {:events [(create-test-event :document/created 
                    {:text "Test doc" :document-id doc-id}
                    :tags #{[:document doc-id]})]})
      
      ;; Execute with read model
      (let [config [:sequence [:action :succeed]]
            {:keys [result blackboard]} (bt/execute 
                                          event-store config
                                          :read-model-fn test-read-model
                                          :domain-event-config [{:types #{:document/created}
                                                                 :tags #{[:document doc-id]}}])]
        
        (is (= success result))
        (is (= "Test doc" (bt/get-value blackboard :document)))
        (is (= doc-id (bt/get-value blackboard :document-id)))))))

;; =============================================================================
;; Error Cases
;; =============================================================================

(deftest test-blackboard-with-invalid-read-model
  (testing "Blackboard handles read model errors gracefully"
    (let [event-store (create-test-event-store)
          failing-read-model (fn [_events] (throw (Exception. "Read model error")))]
      
      ;; Should not throw, but might have empty state
      (is (some? (bb/create-event-sourced-blackboard 
                   event-store
                   :read-model-fn failing-read-model))))))

(deftest test-blackboard-with-empty-domain-config
  (testing "Blackboard works with no domain event config"
    (let [event-store (create-test-event-store)
          blackboard (bb/create-event-sourced-blackboard 
                       event-store
                       :domain-event-config [])]
      
      ;; Should work normally for blackboard operations
      (bt/set-value blackboard :key "value")
      (is (= "value" (bt/get-value blackboard :key))))))
