(ns com.potetm.promise
  (:refer-clojure :exclude [resolve])
  (:require-macros
    [com.potetm.promise :as p]))


(defn promise [f]
  (js/Promise. f))


(defn wrap [f]
  (js/Promise. (fn [yes no]
                 (yes (f)))))


(defn all [coll]
  (js/Promise.all coll))


(defn all-settled [coll]
  (js/Promise.allSettled coll))


(defn resolve
  ([]
   (js/Promise.resolve))
  ([v]
   (js/Promise.resolve v)))


(defn reject
  ([]
   (js/Promise.reject))
  ([v]
   (js/Promise.reject v)))


(defn timeout
  ([ms]
   (js/Promise. (fn [yes no]
                  (js/setTimeout #(yes nil)
                                 ms))))
  ([f ms]
   (js/Promise. (fn [yes no]
                  (js/setTimeout #(yes (f))
                                 ms)))))

(defn timing [promise]
  (let [start (js/Date.)]
    (p/await [ret promise]
      [(- (.getTime (js/Date.))
          (.getTime start))
       ret])))
