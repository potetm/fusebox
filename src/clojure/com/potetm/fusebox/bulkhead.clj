(ns com.potetm.fusebox.bulkhead
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent Semaphore
                          TimeUnit)))


(defn bulkhead [{c ::concurrency :as spec}]
  (merge {::sem (Semaphore. c)}
         spec))


(defn with-bulkhead* [{^Semaphore s ::sem
                       to ::timeout-ms :as spec}
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
                          {::fb/error ::timeout-waiting-for-bulkhead
                           ::fb/spec (util/pretty-spec spec)}))))


(defmacro with-bulkhead [spec & body]
  `(with-bulkhead* ~spec
                   (^{:once true} fn* [] ~@body)))


(defn shutdown [{^Semaphore s ::sem}]
  ;; Don't allow any more processes to acquire any more permits
  (.drainPermits s))
