(ns com.potetm.fusebox.timeout
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.error :as-alias err]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ExecutorService
                          Executors
                          ThreadFactory
                          TimeUnit
                          TimeoutException)
    (java.util.concurrent.atomic AtomicLong)))


(set! *warn-on-reflection* true)


(defmacro try-interruptible
  "Same as clojure.core/try, but guarantees an InterruptedException will be
  rethrown and never swallowed.

  This should be preferred to clojure.core/try for calls to with-timeout."
  [& body]
  `(util/try-interruptible ~@body))


(defn init
  "Initialize a Timeout.

  spec is a map containing:
    ::timeout-ms - millis to wait before timing out
    ::interrupt? - bool indicated whether a timed-out thread should be interrupted
                   on timeout"
  [{_to ::timeout-ms :as spec}]
  (util/assert-keys "Timeout"
                    {:req-keys [::timeout-ms]
                     :opt-keys [::interrupt?]}
                    spec)
  (merge {::interrupt? true}
         spec))


(defonce ^:private
  timeout-threadpool
  (delay (Executors/newCachedThreadPool (let [tc (AtomicLong. -1)]
                                          (reify ThreadFactory
                                            (newThread [this r]
                                              (doto (Thread. r)
                                                (.setName (str "fusebox-thread-"
                                                               (.incrementAndGet tc)))
                                                (.setDaemon true))))))))


(defn- binding-conveyor-fn
  {:private true
   :added "1.3"}
  [f]
  (let [frame (clojure.lang.Var/cloneThreadBindingFrame)]
    (fn
      ([]
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (f))
      ([x]
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (f x))
      ([x y]
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (f x y))
      ([x y z]
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (f x y z))
      ([x y z & args]
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (apply f x y z args)))))

(defn timeout* [{to ::timeout-ms
                 intr? ::interrupt?}
                f]
  (if-not to
    (f)
    (let [fut (.submit ^ExecutorService @timeout-threadpool
                       ^Callable (binding-conveyor-fn f))]
      (try
        (.get fut
              to
              TimeUnit/MILLISECONDS)
        ;; This *mustn't* be a try-interruptible because we need to cancel the future.
        (catch InterruptedException ie
          (.cancel fut intr?)
          (throw ie))
        (catch TimeoutException to
          (.cancel fut intr?)
          (throw (ex-info "fusebox timeout"
                          {::fb/error ::err/exec-timeout
                           ::timeout-ms to})))))))


(defmacro with-timeout
  "Evaluates body, throwing ExceptionInfo if lasting longer than specified.

  spec is the return value of init."
  [spec & body]
  `(timeout* ~spec
             (^{:once true} fn* [] ~@body)))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::timeout-ms))
