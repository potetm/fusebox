(ns com.potetm.fusebox.util
  (:import
    (clojure.lang Var)))


(set! *warn-on-reflection* true)


(defn class-for-name [n]
  (try
    (Class/forName n)
    (catch ClassNotFoundException _)))


(defn convey-bindings [f]
  (let [binds (Var/getThreadBindingFrame)]
    (fn []
      (Var/resetThreadBindingFrame binds)
      (f))))


(defmacro try-interruptible
  "Guarantees that an InterruptedException will be immediately rethrown.

  This is preferred to clojure.core/try inside a with-timeout call."
  [& body]
  (let [[pre-catch catches+finally] (split-with (fn [n]
                                                  (not (and (list? n)
                                                            (or (= (first n)
                                                                   'catch)
                                                                (= (first n)
                                                                   'finally)))))
                                                body)]
    `(try
       (do ~@pre-catch)
       (catch InterruptedException ie#
         (throw ie#))
       ~@catches+finally)))


(defn pretty-spec
  ([spec]
   (dissoc spec
           ::circuit-breaker
           ::retry-delay
           ::retry?
           ::bulkhead)))
