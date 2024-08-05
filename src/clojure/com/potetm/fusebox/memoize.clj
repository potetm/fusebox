(ns com.potetm.fusebox.memoize
  (:refer-clojure :exclude [memoize get])
  (:import
    (java.util.concurrent ConcurrentHashMap)
    (java.util.function Function)))


(set! *warn-on-reflection* true)


(defn memoize [spec]
  (merge {::chm (ConcurrentHashMap.)}
         spec))


(defn get [{^ConcurrentHashMap chm ::chm
            f ::fn} & args]
  (.computeIfAbsent chm
                    args
                    (reify Function
                      (apply [this args]
                        (apply f args)))))


(comment
  @(def m (memoize (fn [i]
                     (println "COMPUTING!")
                     (+ i 3))))

  (get m 7))
