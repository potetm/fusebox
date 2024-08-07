(ns com.potetm.fusebox.registry
  (:refer-clojure :exclude [get])
  (:require
    [clojure.core :as cc]))


(def registry
  (atom {}))


(defn register!
  "Register a spec under key k."
  [k spec]
  (swap! registry assoc k spec)
  nil)


(defn un-register!
  "Removes key k from the registry."
  [k]
  (swap! registry dissoc k)
  nil)


(defn get
  "Retrieve the spec associated with k from the registry."
  [k]
  (cc/get @registry k))
