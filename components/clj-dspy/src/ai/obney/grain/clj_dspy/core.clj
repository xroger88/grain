(ns ai.obney.grain.clj-dspy.core
  (:require [libpython-clj2.python :as py :refer [py. py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [clojure.string :as str]))

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
    (let [entries (rest schema)]
      (str "TypedDict('" (gensym "T") "', {"
           (str/join ", " (map (fn [[k v]]
                                 (str "\"" (name k) "\": " (malli-schema->python-type v)))
                               entries))
           "})"))

    ;; Optional types
    (and (vector? schema) (= (first schema) :maybe))
    (str "Optional[" (malli-schema->python-type (second schema)) "]")

    ;; Union types
    (and (vector? schema) (= (first schema) :or))
    (str "Union[" (str/join ", " (map malli-schema->python-type (rest schema))) "]")

    ;; Enum types
    (and (vector? schema) (= (first schema) :enum))
    "str"  ; Pydantic will handle enum validation

    (and (vector? schema) 
         (keyword? (first schema)))
    (malli-schema->python-type (first schema))

    ;; Default fallback
    :else "Any"))

(defn parse-malli-field
  "Normalize field schema input and pull out user metadata.
  supports both [:string {:desc ...}] and nested [:string {...} :and ...]."
  [raw-schema]
  (let [form     (m/form raw-schema)
        props?   (and (vector? form) (map? (second form)))
        props    (when props? (second form))
        ;; properties as built via `m/keyword`, expanded syntax and reused on walk
        meta     (m/properties raw-schema)
        cfg      ;; pick merge of both maps, giving priority to second-element props
        (cond
          props    (merge meta props)
          meta     meta
          :else    {})]
    {:schema  (cond
                ;; body-schema should skip props map at index 1
                props?   (into [(first form)]
                               (drop 2 form)) 
                :else     raw-schema)
     :desc     (or (:desc cfg) (:description cfg))
     :default  (get cfg :default)}))

(defn build-pydantic-field-args
  "Builds the arg list for Pydantic Field() call from optional default and description."
  [{:keys [desc default]}]
  (->> [(when (some? default)
          (format "default=%s" (pr-str default)))
        (when desc
          (format "description=%s" (pr-str desc)))]
       (remove nil?)
       (str/join ", ")))

(defn malli-fields->model-class
  "Generates a string for a Pydantic BaseModel subclass with typed and documented fields."
  [fields class-name]
  (let [lines
        (for [[field-key raw-schema] fields]
          (let [{:keys [schema desc default]} (parse-malli-field raw-schema)
                py-type (malli-schema->python-type schema) 
                field-args (build-pydantic-field-args {:desc desc :default default})
                line (if (str/blank? field-args)
                       (format "    %s: %s\n" (name field-key) py-type)
                       (format "    %s: %s = Field(%s)\n"
                               (name field-key) py-type field-args))]
            line))]
    (str "class " class-name "(BaseModel):\n"
         (if (seq lines)
           (apply str lines)
           "    pass\n")
         "\n")))

(defn generate-signature
  [name docstring inputs outputs module]
  (let [model-name name
        input-model (str model-name "Inputs")
        output-model (str model-name "Outputs")
        python-code (str
                     "import dspy\n"
                     "from pydantic import BaseModel, Field\n"
                     "from typing import List, Dict, Optional, Union, Any, TypedDict\n"
                     "import types\n"
                     "if '" module "' not in globals():\n"
                     "    " module " = types.ModuleType('" module "')\n"
                     "    globals()['" module "'] = " module "\n\n"
                     ;; Emit BaseModel definitions
                     (malli-fields->model-class inputs input-model)
                     (malli-fields->model-class outputs output-model)
                     ;; Now the DSPy signature class referencing those models
                     "class " model-name "(dspy.Signature):\n"
                     (when docstring (str "    \"\"\"" docstring "\"\"\"\n"))
                     "    inputs: " input-model " = dspy.InputField()\n"
                     "    outputs: " output-model " = dspy.OutputField()\n"
                     "\n"
                     "setattr(" module ", '" model-name "', " model-name ")\n"
                     "globals()['" module "." model-name "'] = " model-name "\n"
                     
                     
                     "setattr(" module ", '" model-name "Inputs" "', " model-name "Inputs" ")\n"
                     "globals()['" module "." model-name "Inputs" "'] = " model-name "Inputs" "\n"
                     
                     
                     "setattr(" module ", '" model-name "Outputs" "', " model-name "Outputs" ")\n"
                     "globals()['" module "." model-name "Outputs" "'] = " model-name "Outputs" "\n")] 
    (py/run-simple-string python-code)
    (py/get-item (py/module-dict (py/import-module "__main__")) (str module "." model-name))))

(defmacro defsignature
  "Core implementation of defsignature macro."
  [name & args]
  (let [[docstring spec] (if (string? (first args))
                           [(first args) (second args)]
                           [nil (first args)])
        {:keys [inputs outputs]} spec
        clj-namespace (str *ns*)
        module (-> clj-namespace (str/replace #"\." "_") (str/replace #"-" "_"))
        ;; Create metadata map with signature information under :dspy key
        signature-metadata {:dspy/signature {:module module
                                             :signature name
                                             :inputs inputs
                                             :outputs outputs
                                             :instructions docstring}}]
    `(do
       (let [signature-class# (generate-signature ~(str name) ~docstring ~inputs ~outputs ~module)]
         (def ~(with-meta name (merge signature-metadata
                                      (when docstring {:doc docstring})))
           signature-class#)))))
