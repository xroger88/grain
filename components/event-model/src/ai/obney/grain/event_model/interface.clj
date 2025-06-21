(ns ai.obney.grain.event-model.interface
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [malli.core :as m]))

(defschemas event-model
  {:command-name [:fn #(and (qualified-keyword? %)
                            (= "command" (namespace %)))]

   :event-name [:fn #(and (qualified-keyword? %)
                          (= "event" (namespace %)))]

   :view-name [:fn #(and (qualified-keyword? %)
                         (= "view" (namespace %)))]

   :todo-processor-name [:fn #(and (qualified-keyword? %)
                                   (= "todo-processor" (namespace %)))]

   :screen-name [:fn #(and (qualified-keyword? %)
                           (= "screen" (namespace %)))]

   :flow-name [:fn #(and (qualified-keyword? %)
                         (= "flow" (namespace %)))]

   :malli-schema [:fn #(m/schema?
                        (try
                          (m/schema %)
                          (catch Exception _e)))]

   :given-when-then [:map
                     [:given :string]
                     [:when :string]
                     [:then :string]]

   :given-when-thens [:vector :given-when-then]

   :command [:map
             [:name :command-name]
             [:description :string]
             [:given-when-thens {:optional true} :given-when-thens]
             [:schema :malli-schema]]

   :event [:map
           [:name :event-name]
           [:description :string]
           [:schema :malli-schema]]

   :view [:map
          [:name :view-name]
          [:description :string]
          [:schema :malli-schema]]

   :todo-processor [:map
                    [:name :todo-processor-name]
                    [:description :string]]

   :screen [:map
            [:name :screen-name]
            [:description :string]]

   :commands [:map-of :command-name :command]

   :events [:map-of :event-name :event]

   :views [:map-of :view-name :view]

   :todo-processors [:map-of :todo-processor-name :todo-processor]

   :screens [:map-of :screen-name :screen]

   :valid-step [:fn #(let [connect-from-type (when (:from %) (namespace (:from %)))
                           connect-to-type (when (:to %) (namespace (:to %)))]
                       (case connect-from-type
                         "view" (contains? #{"todo-processor" "screen" nil} connect-to-type)
                         "todo-processor" (contains? #{"command" nil} connect-to-type)
                         "screen" (contains? #{"command" nil} connect-to-type)
                         "command" (contains? #{"event" nil} connect-to-type)
                         "event" (contains? #{"view" nil} connect-to-type)
                         nil true))]

   :step [:and
          :valid-step
          [:map
           [:from [:or
                   :command-name
                   :event-name
                   :view-name
                   :todo-processor-name
                   :screen-name
                   :nil]]
           [:to [:or
                 :command-name
                 :event-name
                 :view-name
                 :todo-processor-name
                 :screen-name
                 :nil]]]]

   :flow [:map
          [:name :flow-name]
          [:description :string]
          [:steps [:vector :step]]]

   :flows [:map-of :flow-name :flow]

   :event-model
   [:map
    [:version [:enum 1]]
    [:commands :commands]
    [:events :events]
    [:views :views]
    [:todo-processors :todo-processors]
    [:screens :screens]
    [:flows :flows]]})

