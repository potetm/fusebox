(ns com.potetm.fusebox.registry
  (:refer-clojure :exclude [get])
  (:require
    [clojure.core :as cc]))


(def registry
  (atom {}))


(defn register! [k spec]
  (swap! registry assoc k spec)
  nil)


(defn un-register! [k spec]
  (swap! registry dissoc k spec)
  nil)


(defn get [k]
  (cc/get @registry k))
