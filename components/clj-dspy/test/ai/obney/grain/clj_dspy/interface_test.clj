(ns ai.obney.grain.clj-dspy.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.clj-dspy.interface :as dspy]))

(deftest test-python-initialization
  (testing "Python initialization should be idempotent"
    (is (nil? (dspy/initialize-python!)))
    (is (nil? (dspy/initialize-python!)))))

(deftest test-schema-parsing
  (testing "Schema parsing should handle various Malli formats"
    (let [core (requiring-resolve 'ai.obney.grain.clj-dspy.core/parse-malli-field)]
      ;; Simple keyword
      (is (= {:schema :string :desc nil :default nil}
             (core :string)))
      
      ;; Vector with metadata
      (is (= {:schema :string :desc "A string field" :default "default-value"}
             (core [:string {:desc "A string field" :default "default-value"}])))
      
      ;; Complex schema
      (is (= {:schema [:vector :string] :desc "List of strings" :default nil}
             (core [:vector {:desc "List of strings"} :string]))))))

(deftest test-type-conversion
  (testing "Malli to Python type conversion"
    (let [convert (requiring-resolve 'ai.obney.grain.clj-dspy.core/malli-schema->python-type)]
      (is (= "str" (convert :string)))
      (is (= "int" (convert :int)))
      (is (= "List[str]" (convert [:vector :string])))
      (is (= "Optional[str]" (convert [:maybe :string]))))))

(deftest test-signature-creation
  (testing "Signature creation with namespacing"
    ;; Test signature macro expansion
    (dspy/defsignature TestSig
      "Test signature"
      {:inputs {:input [:string {:desc "Test input"}]}
       :outputs {:output [:string {:desc "Test output"}]}})

    (is (= :pyobject (type TestSig)))

    (let [metadata (:dspy/signature (meta #'TestSig))]
      (is (map? metadata))
      (is (contains? metadata :signature))
      (is (contains? metadata :inputs))
      (is (contains? metadata :outputs))
      (is (contains? metadata :instructions)))))

(deftest test-model-creation
  (testing "Pydantic model creation with namespacing"
    (let [fields {:name [:string {:desc "Name field"}]
                  :age [:int {:desc "Age field"}]}]

      (dspy/defmodel TestModel fields)

      (is (= fields (-> #'TestModel meta :dspy/model :fields)))

      (is (some? TestModel))

      ;; Test validation
      (let [result (dspy/validate TestModel {:name "John" :age 30})]
        (is (some? result))))))

(deftest test-inspection
  (testing "Python object inspection"
    (dspy/defsignature InspectSig
      "Signature for inspection"
      {:inputs {:test [:string]}
       :outputs {:result [:string]}})
    
    (let [inspection (dspy/inspect-python InspectSig)]
      (is (map? inspection))
      (is (contains? inspection :class-name))
      (is (contains? inspection :class-type))
      (is (contains? inspection :signature-info)))))

