(ns com.potetm.fusebox.timeout
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.time Duration)
    (java.util.concurrent ExecutorService
                          Executors
                          ThreadFactory
                          TimeUnit
                          TimeoutException)
    (java.util.concurrent.atomic AtomicLong)))


(set! *warn-on-reflection* true)


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
                 intr? ::interrupt?
                 :or {intr? true}
                 :as spec}
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
  "Evaluates body, aborting if it lasts longer than specified.

  spec is map containing:
    ::timeout-ms - The timeout in milliseconds"
  [spec & body]
  `(timeout* ~spec
             (^{:once true} fn* [] ~@body)))
