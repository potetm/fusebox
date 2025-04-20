(ns com.potetm.fusebox.util
  (:import
    (java.util.concurrent ExecutorService)))


(set! *warn-on-reflection* true)


(comment
  (defn class-for-name [n]
    (try
      (Class/forName n)
      (catch ClassNotFoundException _)))


  (defn platform-threads? []
    (boolean (when-some [v (System/getProperty "fusebox.usePlatformThreads")]
               (parse-boolean v))))


  ;; No point in allocating a new virtual executor on every invocation. It will
  ;; always spawn a new thread per task, so might as well share the startup
  ;; overhead AND allow people to precompile without eval.
  (def ^ExecutorService
    virtual-exec
    (when (and (not (platform-threads?))
               (class-for-name "java.lang.VirtualThread"))
      (eval '(java.util.concurrent.Executors/newThreadPerTaskExecutor
               (-> (Thread/ofVirtual)
                   (.name "fusebox-thread-" 1)
                   (.factory)))))))


(defn assert-keys [n {req :req-keys :as deets} spec]
  (let [ks' (into #{}
                  (remove (fn [k]
                            (get spec k)))
                  req)]
    (when (seq ks')
      (throw (ex-info (str "Invalid " n)
                      (merge deets
                             {:missing-keys ks'}))))))


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
