(ns com.potetm.fusebox.bulkhead
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.error :as-alias err]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent Semaphore
                          TimeUnit)))


(defn init
  "Initialize a bulkhead (i.e. concurrency limiter).

  spec is a map containing:
    ::concurrency     - the integer number of concurrent callers to allow
    ::wait-timeout-ms - max millis a thread will wait to enter bulkhead"
  [{c ::concurrency
    _wt ::wait-timeout-ms :as spec}]
  (util/assert-keys "Bulkhead"
                    {:req-keys [::concurrency
                                ::wait-timeout-ms]}
                    spec)
  (merge {::sem (Semaphore. c)}
         spec))


(defn with-bulkhead* [{^Semaphore s ::sem
                       to ::wait-timeout-ms :as spec}
                      f]
  (cond
    (not s) (f)

    (.tryAcquire s
                 to
                 TimeUnit/MILLISECONDS)
    (util/try-interruptible
      (f)
      (finally
        (.release s)))

    :else (throw (ex-info "fusebox timeout"
                          {::fb/error ::err/timeout-waiting-for-bulkhead
                           ::fb/spec (util/pretty-spec spec)}))))


(defmacro with-bulkhead
  "Evaluates body, guarded by the provided bulkhead."
  [spec & body]
  `(with-bulkhead* ~spec
                   (^{:once true} fn* [] ~@body)))


(defn shutdown [{^Semaphore s ::sem}]
  ;; Don't allow any more processes to acquire any more permits
  (.drainPermits s)
  nil)
