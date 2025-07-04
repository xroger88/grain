(ns ai.obney.grain.clj-dspy.core
  (:require [libpython-clj2.python :as py :refer [py. py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [clojure.string :as str]))

(defonce ^:private python-initialized? (atom false))

(defn initialize-python!
  "Initialize Python and import required modules. Safe to call multiple times."
  []
  (when-not @python-initialized?
    (py/initialize!)
    (require-python '[dspy :as dspy]
                    '[typing :refer [List Dict Optional Union]])
    (reset! python-initialized? true)))

(defn malli-schema->python-type 
  "Convert a Malli schema to a Python type string."
  [schema]
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

(defn build-field-args 
  "Build field arguments string for DSPy fields."
  [desc default]
  (str (when desc (str "desc=\"" desc "\""))
       (when (and desc default) ", ")
       (when default (str "default=" (pr-str default)))))

(defn parse-malli-field 
  "Parse a Malli schema to extract schema, description, and default.
   Validates that the schema is a proper Malli schema."
  [schema]
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

(defn malli-fields->pydantic 
  "Convert Malli field definitions to Pydantic field strings."
  [fields field-type]
  (apply str
         (for [[field-name schema] fields]
           (let [{:keys [schema desc default]} (parse-malli-field schema)
                 python-type (malli-schema->python-type schema)
                 field-args (build-field-args desc default)]
             (str "    " (name field-name) ": " python-type " = dspy." field-type "(" field-args ")\n")))))


(defn generate-signature 
  "Generate a DSPy signature class with namespacing support."
  [name docstring inputs outputs clj-namespace]
  (initialize-python!)
  (let [;; Create Python module name from Clojure namespace
        python-module (-> clj-namespace
                          (str/replace #"\." "_")
                          (str/replace #"-" "_"))
        model-name (str name)
        qualified-name (str python-module "." model-name)
        python-code (str "import dspy\n"
                         "from pydantic import BaseModel\n"
                         "from typing import List, Dict, Optional, Union, Any\n"
                         "import types\n"
                         ;; Create module if it doesn't exist
                         "if '" python-module "' not in globals():\n"
                         "    " python-module " = types.ModuleType('" python-module "')\n"
                         "    globals()['" python-module "'] = " python-module "\n"
                         "class " model-name "(dspy.Signature):\n"
                         (when docstring (str "    \"\"\"" docstring "\"\"\"\n"))
                         (malli-fields->pydantic inputs "InputField")
                         (malli-fields->pydantic outputs "OutputField")
                         ;; Add class to the module
                         "setattr(" python-module ", '" model-name "', " model-name ")\n"
                         ;; Also add to globals for easy access
                         "globals()['" qualified-name "'] = " model-name "\n")]
    (py/run-simple-string python-code)
    (py/get-item (py/module-dict (py/import-module "__main__")) qualified-name)))

(defn validate-inputs 
  "Validate input data against input schemas."
  [input-schemas input-data]
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

(defn validate-outputs 
  "Validate output data against output schemas."
  [output-schemas output-data]
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

(defn build-field-args-python 
  "Build field arguments for Pydantic models."
  [desc default]
  (str (when desc (str "description=\"" desc "\""))
       (when (and desc default) ", ")
       (when default
         (str "default="
              (cond
                (boolean? default) (if default "True" "False")
                (string? default) (str "\"" default "\"")
                :else (str default))))))

(defn generate-pydantic-model 
  "Generate a Pydantic model class with namespacing support."
  [model-name fields clj-namespace]
  (initialize-python!)
  (let [python-module (-> clj-namespace
                          (str/replace #"\." "_")
                          (str/replace #"-" "_"))
        qualified-name (str python-module "." model-name)
        python-code (str "import pydantic\n"
                         "from pydantic import BaseModel, Field\n"
                         "from typing import List, Dict, Optional, Union, Any\n"
                         "import types\n"
                         ;; Create module if it doesn't exist
                         "if '" python-module "' not in globals():\n"
                         "    " python-module " = types.ModuleType('" python-module "')\n"
                         "    globals()['" python-module "'] = " python-module "\n"
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
                         ;; Add class to the module
                         "setattr(" python-module ", '" model-name "', " model-name ")\n"
                         ;; Also add to globals for easy access
                         "globals()['" qualified-name "'] = " model-name "\n")]
    (py/run-simple-string python-code)
    (py/get-item (py/module-dict (py/import-module "__main__")) qualified-name)))

(defn validate-with-model
  "Validate data against a Pydantic model. Returns the validated model instance."
  [model data]
  (py/call-attr model "model_validate" data))

(defn inspect-python-obj
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

(defmacro defsignature
  "Core implementation of defsignature macro."
  [name & args]
  (let [[docstring spec] (if (string? (first args))
                           [(first args) (second args)]
                           [nil (first args)])
        {:keys [inputs outputs]} spec
        clj-namespace (str *ns*)
        ;; Create metadata map with signature information under :dspy key
        signature-metadata {:dspy/signature {:signature name
                                             :inputs inputs
                                             :outputs outputs
                                             :instructions docstring}}]
    `(do
       (let [signature-class# (generate-signature ~(str name) ~docstring ~inputs ~outputs ~clj-namespace)]
         (def ~(with-meta name (merge signature-metadata
                                      (when docstring {:doc docstring})))
           signature-class#)))))

(defmacro defmodel
  "Core implementation of defmodel macro."
  [name fields]
  (let [;; Create metadata map with model information under :dspy key
        model-metadata {:dspy/model {:fields fields}}]
    `(def ~(with-meta name model-metadata)
       (generate-pydantic-model ~(str name) ~fields ~(str *ns*)))))



(comment

  (require-python '[dspy :as dspy])

  (def lm (dspy/LM "openai/gpt-4o-mini"))

  (dspy/configure :lm lm)


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
  (let [document "Artificial intelligence is transforming healthcare superbly by enabling faster diagnoses, personalized treatments, and improved patient outcomes. Machine learning algorithms can analyze medical images with remarkable accuracy, often detecting conditions that human doctors might miss. However, challenges remain in terms of data privacy, algorithmic bias, and the need for regulatory frameworks."

        ;; Step 1: Extract key points
        key-points-result ((dspy/Predict ExtractKeyPoints) 
                           :document document)

        ;; Step 2: Analyze sentiment  
        sentiment-result ((dspy/Predict AnalyzeSentiment) 
                          :text document)

        ;; Step 3: Generate title
        title-result ((dspy/Predict GenerateTitle)
                      :document document
                      :key_points (py.- key-points-result :points))

        ;; Step 4: Create structured output using our Pydantic model
        summary-data {:title (py.- title-result :title)
                      :key_points (clojure.string/split (py.- key-points-result :points) #",\s*")
                      :sentiment (py.- sentiment-result :sentiment)
                      :word_count (count (clojure.string/split document #"\s+"))}]

    ;; Validate and create the final structured summary
    (validate-with-model DocumentSummary summary-data)

    ;; Return the summary data
    summary-data)
  )

