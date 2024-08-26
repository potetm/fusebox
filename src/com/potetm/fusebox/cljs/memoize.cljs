(ns com.potetm.fusebox.cljs.memoize
  (:refer-clojure :exclude [get])
  (:require
    [clojure.core :as cc]
    [com.potetm.fusebox.cljs.util :as util]))


(def not-found (js-obj))


(defn init
  "Initialize a memoized function.

  spec is a map containing:
    ::fn - The function to memoize

  ::fn is guaranteed to be called once."
  [{_fn ::fn :as spec}]
  (util/assert-keys "Memoize"
                    {:req-keys [::fn]}
                    spec)
  (merge {::vol (volatile! {})}
         spec))


(defn get
  "Retrieve a value, invoking ::fn if necessary."
  [{f ::fn
    vol ::vol} & args]
  (if-not vol
    (apply f args)
    (let [v (cc/get @vol args not-found)]
      (if (identical? v not-found)
        (let [ret (apply f args)]
          (vswap! vol
                  assoc
                  args
                  ret)
          ret)
        v))))


(defn shutdown [_spec])


(defn disable [spec]
  (dissoc spec ::vol))
