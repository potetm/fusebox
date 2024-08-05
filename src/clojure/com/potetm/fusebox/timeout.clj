(ns com.potetm.fusebox.timeout
  (:require
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ExecutorService
                          Executors
                          ThreadFactory
                          TimeUnit
                          TimeoutException)
    (java.util.concurrent.atomic AtomicLong)))


(set! *warn-on-reflection* true)


(def virtual-exec
  (when (util/class-for-name "java.lang.VirtualThread")
    (eval '(Executors/newThreadPerTaskExecutor (-> (Thread/ofVirtual)
                                                   (.name "fusebox-thread-" 1)
                                                   (.factory))))))


(defonce ^{:doc "An unbound threadpool used for reliably timing out calls."
           :private true}
  timeout-threadpool
  (delay (or virtual-exec
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
  (let [fut (.submit ^ExecutorService @timeout-threadpool
                     ^Callable (util/convey-bindings f))]
    (try
      (.get fut
            to
            TimeUnit/MILLISECONDS)
      (catch InterruptedException ie
        (.cancel fut intr?)
        (throw ie))
      (catch TimeoutException to
        (.cancel fut intr?)
        (throw (ex-info "fusebox timeout"
                        {::error ::error-exec-timeout
                         ::spec (util/pretty-spec spec)}))))))


(defmacro with-timeout
  "Evaluates body, aborting if it lasts longer than specified.

  spec is map containing:
    ::timeout-ms - The timeout in milliseconds"
  [spec & body]
  `(timeout* ~spec
             (^{:once true} fn* [] ~@body)))
