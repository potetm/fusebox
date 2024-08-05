(ns com.potetm.fusebox.bulkhead
  (:require
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
  (when s
    (if (.tryAcquire s
                     to
                     TimeUnit/MILLISECONDS)
      (try (f)
           (finally
             (.release s)))
      (throw (ex-info "fusebox timeout"
                      {::error :error-timeout
                       ::spec (util/pretty-spec spec)})))))


(defmacro with-bulkhead [spec & body]
  `(with-bulkhead* ~spec
                   (^{:once true} fn* [] ~@body)))


(defn shutdown [{^Semaphore s ::sem}]
  ;; Don't allow any more processes to acquire any more permits
  (.drainPermits s))
