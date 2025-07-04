(ns blah
  (:require [ai.obney.grain.anomalies.interface]
            [ai.obney.grain.command-processor.interface]
            [ai.obney.grain.command-request-handler.interface]
            [ai.obney.grain.command-processor.interface.schemas]
            [ai.obney.grain.core-async-thread-pool.interface]
            [ai.obney.grain.event-store-v2.interface.schemas]
            [ai.obney.grain.event-store-v2.interface]
            [ai.obney.grain.event-store-postgres.interface]
            [ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface]
            [ai.obney.grain.periodic-task.interface]
            [ai.obney.grain.pubsub.interface]
            [ai.obney.grain.query-processor.interface]
            [ai.obney.grain.query-request-handler.interface]
            [ai.obney.grain.query-schema.interface]
            [ai.obney.grain.time.interface]
            [ai.obney.grain.webserver.interface]
            [ai.obney.grain.todo-processor.interface]
            [ai.obney.grain.schema-util.interface]
            [libpython-clj2.python :as py :refer [py. py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]
            [clojure.string :as str]))

;; Initialize Python
(py/initialize!)

(require-python '[dspy :as dspy]
                '[typing :refer [List Dict Optional Union]])


(defn malli-schema->python-type [schema]
  (cond
    ;; Basic types
    (= schema :string) "str"
    (= schema :int) "int"
    (= schema :double) "float"
    (= schema :boolean) "bool"
    (= schema :any) "Any"

    ;; Collections
    (and (vector? schema) (= (first schema) :vector))
    (str "List[" (malli-schema->python-type (second schema)) "]")

    (and (vector? schema) (= (first schema) :map))
    (if (= (count schema) 2)
      "Dict[str, Any]"  ; Generic map
      (str "Dict[str, " (malli-schema->python-type (get-in schema [1 :values])) "]"))

    ;; Optional types
    (and (vector? schema) (= (first schema) :maybe))
    (str "Optional[" (malli-schema->python-type (second schema)) "]")

    ;; Union types
    (and (vector? schema) (= (first schema) :or))
    (str "Union[" (str/join ", " (map malli-schema->python-type (rest schema))) "]")

    ;; Enum types
    (and (vector? schema) (= (first schema) :enum))
    "str"  ; Pydantic will handle enum validation

    ;; Custom schemas (registered models)
    (keyword? schema)
    (str (name schema))

    ;; Default fallback
    :else "Any"))

(defn build-field-args [desc default]
  (str (when desc (str "desc=\"" desc "\""))
       (when (and desc default) ", ")
       (when default (str "default=" (pr-str default)))))

(defn parse-malli-field [schema]
  "Parse a Malli schema to extract schema, description, and default.
   Validates that the schema is a proper Malli schema."
  (try
    ;; First validate it's a proper Malli schema
    (m/schema schema)
    (cond
      ;; Vector format like [:string {:desc "description" :default "value"}]
      (and (vector? schema) (map? (second schema)))
      (let [schema-type (if (> (count schema) 2)
                          ;; For schemas like [:vector {:desc "..."} :string]
                          (vec (cons (first schema) (drop 2 schema)))
                          ;; For schemas like [:string {:desc "..."}]
                          (first schema))
            metadata (second schema)]
        {:schema schema-type
         :desc (:desc metadata)
         :default (:default metadata)})

      ;; Vector format like [:string {:description "description"}] (standard Malli)
      (and (vector? schema) (map? (second schema)) (:description (second schema)))
      (let [schema-type (if (> (count schema) 2)
                          (vec (cons (first schema) (drop 2 schema)))
                          (first schema))
            metadata (second schema)]
        {:schema schema-type
         :desc (:description metadata)
         :default (:default metadata)})

      ;; Simple keyword like :string
      (keyword? schema)
      {:schema schema :desc nil :default nil}

      ;; Already in old format {:schema :string :desc "..."}
      (and (map? schema) (:schema schema))
      schema

      ;; Default - treat as schema (should be valid Malli at this point)
      :else
      {:schema schema :desc nil :default nil})
    (catch Exception e
      (throw (ex-info (str "Invalid Malli schema: " schema)
                      {:schema schema
                       :error (.getMessage e)})))))

(defn malli-fields->pydantic [fields field-type]
  (apply str
         (for [[field-name schema] fields]
           (let [{:keys [schema desc default]} (parse-malli-field schema)
                 python-type (malli-schema->python-type schema)
                 field-args (build-field-args desc default)]
             (str "    " (name field-name) ": " python-type " = dspy." field-type "(" field-args ")\n")))))

(defn malli->pydantic-signature [model-name inputs outputs doc]
  (str "import dspy\n"
       "from pydantic import BaseModel\n"
       "from typing import List, Dict, Optional, Union, Any\n\n"
       "class " model-name "(dspy.Signature):\n"
       ;; The docstring IS the instructions
       (when doc (str "    \"\"\"" doc "\"\"\"\n"))
       (malli-fields->pydantic inputs "InputField")
       (malli-fields->pydantic outputs "OutputField")))

(defmacro defsignature
  [name & args]
  (let [[docstring spec] (if (string? (first args))
                           [(first args) (second args)]
                           [nil (first args)])
        {:keys [inputs outputs]} spec
        model-name (str name)
        python-code (str "import dspy\n"
                         "from pydantic import BaseModel\n"
                         "from typing import List, Dict, Optional, Union, Any\n"
                         "class " model-name "(dspy.Signature):\n"
                         (when docstring (str "    \"\"\"" docstring "\"\"\"\n"))
                         (malli-fields->pydantic inputs "InputField")
                         (malli-fields->pydantic outputs "OutputField")
                         "globals()['" model-name "'] = " model-name "\n")]
    `(do
       (py/run-simple-string ~python-code)
       (let [signature-class# (py/get-item (py/module-dict (py/import-module "__main__")) ~model-name)]
         (def ~name
           ~@(when docstring [docstring])
           {:signature signature-class#
            :inputs ~inputs
            :outputs ~outputs
            :instructions ~docstring
            :predict (fn [input-map#]
                       (validate-inputs ~inputs input-map#)
                       (let [dspy# (py/import-module "dspy")
                             predictor# (py/call-attr dspy# "Predict" signature-class#)
                             ;; Convert Clojure map to Python kwargs
                             python-kwargs# (into {} (for [[k# v#] input-map#]
                                                       [(name k#) v#]))
                             raw-result# (py/call-attr-kw predictor# "__call__" [] python-kwargs#)
                             jvm-result# (py/->jvm raw-result#)
                             ;; Convert string keys to keyword keys for easier access
                             result# (into {} (for [[k# v#] jvm-result#]
                                                [(keyword k#) v#]))]
                         (validate-outputs ~outputs jvm-result#)  ; Still validate with string keys
                         result#))})))))

(defn validate-inputs [input-schemas input-data]
  (doseq [[field-name schema] input-schemas]
    (let [parsed-schema (parse-malli-field schema)
          malli-schema (:schema parsed-schema)
          value (get input-data (keyword field-name))]
      (when-not (m/validate malli-schema value)
        (throw (ex-info (str "Invalid input for field " field-name)
                        {:field field-name
                         :schema malli-schema
                         :value value
                         :errors (m/explain malli-schema value)}))))))

(defn validate-outputs [output-schemas output-data]
  (doseq [[field-name schema] output-schemas]
    (let [parsed-schema (parse-malli-field schema)
          malli-schema (:schema parsed-schema)
          ;; DSPy returns results with string keys after py/->jvm conversion
          value (get output-data (name field-name))]
      (when-not (m/validate malli-schema value)
        (throw (ex-info (str "Invalid output for field " field-name)
                        {:field field-name
                         :schema malli-schema
                         :value value
                         :available-keys (keys output-data)
                         :errors (m/explain malli-schema value)}))))))

(defn build-field-args-python [desc default]
  (str (when desc (str "description=\"" desc "\""))
       (when (and desc default) ", ")
       (when default
         (str "default="
              (cond
                (boolean? default) (if default "True" "False")
                (string? default) (str "\"" default "\"")
                :else (str default))))))

(defn generate-pydantic-model [model-name fields]
  (let [python-code (str "import pydantic\n"
                         "from pydantic import BaseModel, Field\n"
                         "from typing import List, Dict, Optional, Union, Any\n"
                         "class " model-name "(BaseModel):\n"
                         (apply str
                                (for [[field-name schema] fields]
                                  (let [{:keys [schema desc default]} (parse-malli-field schema)
                                        python-type (malli-schema->python-type schema)
                                        field-args (build-field-args-python desc default)]
                                    (str "    " (name field-name) ": " python-type
                                         (if (or desc default)
                                           (str " = Field(" field-args ")")
                                           "") "\n"))))
                         "globals()['" model-name "'] = " model-name "\n")]
    (py/run-simple-string python-code)
    (py/get-item (py/module-dict (py/import-module "__main__")) model-name)))

(defmacro defmodel
  [name fields]
  `(def ~name (generate-pydantic-model ~(str name) ~fields)))

(defn validate
  "Validate data against a Pydantic model. Returns the validated model instance."
  [model data]
  (py/call-attr model "model_validate" data))

(defn inspect-python
  "Inspect the underlying Python class for signatures or models"
  [python-obj-or-sig-def]
  (let [python-obj (if (map? python-obj-or-sig-def)
                     (:signature python-obj-or-sig-def)  ; Extract from signature def
                     python-obj-or-sig-def)]             ; Use directly for models
    {:class-name (py/get-attr python-obj "__name__")
     :class-type (py/python-type python-obj)
     :docstring (py/get-attr python-obj "__doc__")
     :fields (py/get-attr python-obj "model_fields")
     :field-count (count (py/get-attr python-obj "model_fields"))
     :representation (str python-obj)
     :methods (py/dir python-obj)
     :base-classes (py/get-attr python-obj "__bases__")
     ;; Signature-specific info (only if it's a signature map)
     :signature-info (when (map? python-obj-or-sig-def)
                       {:inputs (->> (:inputs python-obj-or-sig-def)
                                     (map (fn [[k v]] [k (:schema (parse-malli-field v))]))
                                     (into {}))
                        :outputs (->> (:outputs python-obj-or-sig-def)
                                      (map (fn [[k v]] [k (:schema (parse-malli-field v))]))
                                      (into {}))
                        :instructions (:instructions python-obj-or-sig-def)})}))


(defsignature QA
  "Answer the question clearly and concisely"
  {:inputs {:question [:string {:desc "The question to answer"}]}
   :outputs {:answer [:string {:desc "The answer to the question"}]}})

(defmodel User
  {:id [:int {:desc "Unique identifier for the user"}]
   :name [:string {:desc "Name of the user"}]
   :email [:string {:desc "Email address of the user"}]})

(comment
  ;; Example DSPy Flow: Document Summarization System
  ;; This demonstrates using both signatures and models together

  ;; 1. Define a Pydantic model for structured output
  (defmodel DocumentSummary
    {:title [:string {:desc "Summary title"}]
     :key_points [:vector {:desc "Main points from the document"} :string]
     :sentiment [:enum ["positive" "neutral" "negative"] {:desc "Overall sentiment"}]
     :word_count [:int {:desc "Number of words in original document"}]})

  ;; 2. Define DSPy signatures for different tasks
  (defsignature ExtractKeyPoints
    "Extract the main key points from a document"
    {:inputs {:document [:string {:desc "The document text to analyze"}]}
     :outputs {:points [:string {:desc "Comma-separated key points"}]}})

  (defsignature AnalyzeSentiment
    "Analyze the sentiment of text"
    {:inputs {:text [:string {:desc "Text to analyze"}]}
     :outputs {:sentiment [:string {:desc "positive, neutral, or negative"}]}})

  (defsignature GenerateTitle
    "Generate a concise title for a document"
    {:inputs {:document [:string {:desc "The document text"}]
              :key_points [:string {:desc "Main points from the document"}]}
     :outputs {:title [:string {:desc "A concise, descriptive title"}]}})

  ;; 3. Example usage of the complete flow
  (let [document "Artificial intelligence is transforming healthcare largely by enabling faster diagnoses, personalized treatments, and improved patient outcomes. Machine learning algorithms can analyze medical images with remarkable accuracy, often detecting conditions that human doctors might miss. However, challenges remain in terms of data privacy, algorithmic bias, and the need for regulatory frameworks."

        ;; Step 1: Extract key points
        key-points-result ((:predict ExtractKeyPoints)
                           {:document document})

        ;; Step 2: Analyze sentiment  
        sentiment-result ((:predict AnalyzeSentiment)
                          {:text document})

        ;; Step 3: Generate title
        title-result ((:predict GenerateTitle)
                      {:document document
                       :key_points (:points key-points-result)})

        ;; Step 4: Create structured output using our Pydantic model
        summary-data {:title (:title title-result)
                      :key_points (clojure.string/split (:points key-points-result) #",\s*")
                      :sentiment (:sentiment sentiment-result)
                      :word_count (count (clojure.string/split document #"\s+"))}]

    ;; Validate and create the final structured summary
    (validate DocumentSummary summary-data)

    ;; Return the summary data
    summary-data))


