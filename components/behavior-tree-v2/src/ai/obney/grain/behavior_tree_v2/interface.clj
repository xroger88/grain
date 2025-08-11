(ns ai.obney.grain.behavior-tree-v2.interface
  (:require [ai.obney.grain.behavior-tree-v2.core.engine :as core]
            [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]))

(def success p/success)
(def failure p/failure)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def running p/running)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn build
  [config context]
  (core/build config context))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn run 
  [bt]
  (core/run bt))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn st-memory-has-value?
  [{{:keys [_path _schema]} :opts
    :keys [_st-memory]
    :as args}]
  (core/st-memory-has-value? args))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn lt-memory-has-value?
  [{{:keys [_path _schema]} :opts
    :keys [_lt-memory] :as args}]
  (core/lt-memory-has-value? args))