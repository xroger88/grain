(ns ai.obney.grain.behavior-tree.interface-test
  (:require [clojure.test :as test :refer :all]
            [ai.obney.grain.behavior-tree.interface :as sut]
            [ai.obney.grain.behavior-tree.interface.protocols :refer [success]]))

(deftest test-declarative-tree
  (testing "Declarative behavior tree creation"
    (let [config [:sequence
                   [:condition :always-true]
                   [:action :succeed]]
          tree (sut/build-behavior-tree config)
          context {}]
      (is (= success (sut/run-tree tree context))))))

(deftest test-opts-syntax
  (testing "Options map syntax"
    (let [config [:parallel {:success-threshold 2}
                   [:action :succeed]
                   [:action :succeed]]
          tree (sut/build-behavior-tree config)
          context {}]
      (is (= success (sut/run-tree tree context))))))

(deftest test-action-params
  (testing "Action parameters"
    (let [config [:sequence
                   [:action {:message "Hello World!"} :log]
                   [:action {:duration 10} :wait]
                   [:action :succeed]]
          tree (sut/build-behavior-tree config)
          context {}]
      (is (= success (sut/run-tree tree context))))))
