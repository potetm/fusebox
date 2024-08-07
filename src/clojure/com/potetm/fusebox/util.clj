(ns com.potetm.fusebox.util
  (:require
    [com.potetm.fusebox.bulkhead :as-alias bh]
    [com.potetm.fusebox.circuit-breaker :as-alias cb]
    [com.potetm.fusebox.fallback :as-alias fallback]
    [com.potetm.fusebox.memoize :as-alias memo]
    [com.potetm.fusebox.rate-limit :as-alias rl]
    [com.potetm.fusebox.retry :as-alias retry]
    [com.potetm.fusebox.timeout :as-alias to])
  (:import
    (clojure.lang Var)
    (java.util.concurrent ExecutorService)))


(set! *warn-on-reflection* true)


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
    (eval '(Executors/newThreadPerTaskExecutor (-> (Thread/ofVirtual)
                                                   (.name "fusebox-thread-" 1)
                                                   (.factory))))))


(defn assert-keys [n {req :req-keys :as deets} spec]
  (let [ks' (into #{}
                  (remove (fn [k]
                            (get spec k)))
                  req)]
    (when (seq ks')
      (throw (ex-info (str "Invalid " n)
                      (merge deets
                             {:missing-keys ks'}))))))


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
           ::cb/circuit-breaker
           ::cb/success?
           ::memo/fn
           ::fallback/fallback
           ::rl/bg-exec
           ::retry/retry?
           ::retry/delay
           ::retry/success?)))
