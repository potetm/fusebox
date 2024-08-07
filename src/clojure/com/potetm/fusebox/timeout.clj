(ns com.potetm.fusebox.timeout
  (:require
    [com.potetm.fusebox :as-alias fb]
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
  (delay (or util/virtual-exec
             (Executors/newCachedThreadPool (let [tc (AtomicLong. -1)]
                                              (reify ThreadFactory
                                                (newThread [this r]
                                                  (doto (Thread. r)
                                                    (.setName (str "fusebox-thread-"
                                                                   (.incrementAndGet tc)))
                                                    (.setDaemon true)))))))))


(defn timeout* [{to ::timeout-ms
                 intr? ::interrupt? :as spec}
                f]
  (if-not to
    (f)
    (let [fut (.submit ^ExecutorService @timeout-threadpool
                       ^Callable (util/convey-bindings f))]
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
                          {::fb/error ::exec-timeout
                           ::fb/spec (util/pretty-spec spec)})))))))


(defmacro with-timeout
  "Evaluates body, throwing ExceptionInfo if lasting longer than specified.

  spec is the return value of init."
  [spec & body]
  `(timeout* ~spec
             (^{:once true} fn* [] ~@body)))


(defn shutdown [spec])
