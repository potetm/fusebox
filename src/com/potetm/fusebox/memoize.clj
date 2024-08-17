(ns com.potetm.fusebox.memoize
  (:refer-clojure :exclude [memoize get])
  (:require
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ConcurrentHashMap)
    (java.util.function Function)))


(set! *warn-on-reflection* true)


(defn init
  "Initialize a memoized function.

  spec is a map containing:
    ::fn - The function to memoize

  ::fn is guaranteed to be called once."
  [{_fn ::fn :as spec}]
  (util/assert-keys "Memoize"
                    {:req-keys [::fn]}
                    spec)
  (merge {::chm (ConcurrentHashMap.)}
         spec))


(defn get
  "Retrieve a value, invoking ::fn if necessary."
  [{^ConcurrentHashMap chm ::chm
    f ::fn} & args]
  (.computeIfAbsent chm
                    args
                    (reify Function
                      (apply [this args]
                        (apply f args)))))


(defn shutdown [_spec])
