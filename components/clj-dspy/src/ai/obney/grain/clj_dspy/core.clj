(ns ai.obney.grain.clj-dspy.core
  (:require [libpython-clj2.python :as py :refer [py. py.-]]
            [libpython-clj2.require :refer [require-python]]
            [malli.core :as m]
            [malli.registry :as mr]
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
    (let [entries (if (map? (second schema))
                    (drop 2 schema)
                    (rest schema))]
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

(defn expand-with-props
  "Fully expand `schema` using `registry` (map/atom/registry), preserving node + per-key option maps.
   Follows keyword refs via m/deref; guards cycles by keyword."
  ([registry schema]
   (expand-with-props registry schema #{}))
  ([registry schema seen]
   (let [reg (if (instance? clojure.lang.IDeref registry) @registry registry)
         s   (m/schema schema {:registry reg})

         ;; follow refs until not a keyword form (or cycle)
         node (loop [sch s, seen seen]
                (let [f (m/form sch)]
                  (if (and (keyword? f) (not (seen f)))
                    (recur (m/deref sch) (conj seen f))
                    sch)))

         form (m/form node)]

     (cond
       ;; --- MAP: rebuild entries, preserving per-key opts; recurse only into value schema
       (and (vector? form) (= :map (first form)))
       (let [[_ & xs] form
             [opts entries] (if (and (seq xs) (map? (first xs)))
                              [(first xs) (rest xs)]
                              [nil xs])
             props (merge opts (m/properties node))
             expand-entry
             (fn [e]
               (let [[k a b & more] e
                     [kopts v] (if (map? a) [a b] [nil a])]
                 (when (seq more)
                   (throw (ex-info "Unexpected extra items in :map entry" {:entry e})))
                 (let [v' (expand-with-props reg v seen)]
                   (cond-> [k]
                     (seq kopts) (conj kopts)
                     true        (conj v')))))]
         (into [:map]
               (cond-> []
                 (seq props) (conj props)
                 true        (into (map expand-entry entries)))))

       ;; --- MAP-OF: recurse key + value schemas
       (and (vector? form) (= :map-of (first form)))
       (let [[_ & xs] form
             [opts [ksch vsch]] (if (and (seq xs) (map? (first xs)))
                                  [(first xs) (rest xs)]
                                  [nil xs])
             props (merge opts (m/properties node))]
         (into [:map-of]
               (cond-> []
                 (seq props) (conj props)
                 true        (into [(expand-with-props reg ksch seen)
                                    (expand-with-props reg vsch  seen)]))))

       ;; --- Generic vector node (e.g. :vector, :sequential, :tuple, :maybe, :or, ...)
       (vector? form)
       (let [[t & xs] form
             [opts children] (if (and (seq xs) (map? (first xs)))
                               [(first xs) (rest xs)]
                               [nil xs])
             props (merge opts (m/properties node))
             kids  (mapv #(expand-with-props reg % seen) children)]
         (into [t]
               (cond-> []
                 (seq props) (conj props)
                 true        (into kids))))

       ;; --- Leaf (e.g. :string, :int, predicate keywords)
       :else
       (let [props (m/properties node)]
         (if (seq props) [form props] form))))))

(defn parse-malli-field
  "Normalize field schema input and pull out user metadata.
  supports both [:string {:desc ...}] and nested [:string {...} :and ...]."
  [raw-schema]
  (let [raw-schema (expand-with-props (mr/schemas m/default-registry) raw-schema)
        form     (m/form raw-schema)
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


(comment


  (require '[ai.obney.grain.schema-util.interface :refer [defschemas registry*]])




  (defschemas s
    {::a [:map {:desc "A widget"}
          [:question :string]
          [:answer :string]]
     ::b [:vector {:desc "A vector of widgets"} ::a]})

  (expand-with-props registry* ::b)


  (defsignature Test
    "A test signature"
    {:inputs {:b ::b}
     :outputs {:result :string}})

  (require-python '[dspy :as dspy])


  (m/schema [:map {:desc "A widget"}
             [:question :string]
             [:answer :string]])




  (m/deref-recursive ::b)



  (defschemas domain
    {::instruction [:string {:desc "Instruction to help produce an objective"}]
     ::objective [:string {:desc "Objective to be used by the agent"}]
     ::tool-arg [:map
                 [:name {:desc "Name of the argument"} :string]
                 [:description {:desc "Description of the argument"} :string]
                 [:type {:desc "Type of the argument"} :string]]
     ::tools [:vector {:desc "List of tools available to the agent"}
              [:map
               [:name {:desc "Name of the tool"} :string]
               [:priority {:desc "Relative priority of the tool when choosing"} :int]
               [:description {:desc "Description of the tool"} :string]
               [:args {:desc "Arguments for the tool"} [:vector ::tool-arg]]]]
     ::tool-result [:map
                    [:name {:desc "Name of the tool"} :string]
                    [:args {:desc "Arguments for the tool"} [:vector :string]]
                    [:result {:desc "Result of the tool call"} :string]]
     ::tool-results [:vector {:desc "Results from previous tool calls"} ::tool-result]
     ::tool-choice [:string {:desc "Selected tool name"}]
     ::tool-args [:vector {:desc "Arguments for the tool"} :string]
     ::analysis-result [:string {:desc "Result of the analysis :complete, :incomplete"}]
     ::todo-list [:vector [:map [:is_completed :boolean] [:task :string]]]
     ::next-step [:string {:desc "Next step to take"}]
     ::issues-identified [:vector
                          {:desc "A list of dictionaries where the keys are the issue identified from the past tool use and the impact it has on the instruction objective"}
                          [:map
                           [:issue :string]
                           [:impact :string]]]})

  (defsignature SelectTool
    "Use the available tools, tool_results, and reasoning_chain to select a tool to use
   that best advances progress towards the instruction.

   CRITICAL: You must NOT select a tool call that has already occurred. 
   Check tool_results carefully - each entry shows [name, args, result].
   
   If the most recent tool call did not yield good results, try again with new args.

   Prioritize tools that have yielded useful results in similar situations. 
   Avoid tools that have consistently resulted in errors or empty outputs.

   Consider issues_identified from past tool use.

   If you've already tried a tool/args combination, DO NOT try it again.
   
   Act in a way that optimizes context length efficiency.
   
   If no further tool calls are necessary or possible tool_choice should be None"
    {:inputs {:instruction ::instruction
              :reasoning_chain [:vector :string]
              :tool_results ::tool-results
              :tools ::tools
              :issues_identified ::issues-identified
              :next_step ::next-step}
     :outputs {:tool_choice ::tool-choice
               :tool_args ::tool-args}})

  (expand-with-props (mr/schemas m/default-registry) ::tool-results)

  ""
  )