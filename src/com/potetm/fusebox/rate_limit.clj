(ns com.potetm.fusebox.rate-limit
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.error :as-alias err]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent Executors
                          ScheduledExecutorService
                          ScheduledFuture
                          Semaphore
                          ThreadFactory
                          TimeUnit)))


(set! *warn-on-reflection* true)


(defonce ^ScheduledExecutorService exec
  (Executors/newSingleThreadScheduledExecutor
    (reify ThreadFactory
      (newThread [this r]
        (doto (Thread. ^Runnable r)
          (.setName "rate-limiter-bg-thread")
          (.setDaemon true))))))


(defn init
  "Initialize a token bucket rate limiter.

  spec is a map containing:
    ::bucket-size     - the integer number of tokens per period
    ::period-ms       - millis in each period
    ::wait-timeout-ms - max millis a thread waits for a token

 Note: A leaky bucket rate limiter can be easily achieved by setting
 ::bucket-size to 1 and adjusting ::period-ms accordingly."
  [{n ::bucket-size
    p ::period-ms
    _to ::wait-timeout-ms :as spec}]
  (util/assert-keys "Rate Limit"
                    {:req-keys [::bucket-size
                                ::period-ms
                                ::wait-timeout-ms]}
                    spec)
  (let [sem (Semaphore. n)]
    (merge {::sem sem
            ::sched-fut (.scheduleWithFixedDelay exec
                                                 ^Runnable
                                                 (fn []
                                                   (.release sem
                                                             ;; This isn't atomic, but the worst case
                                                             ;; isn't terrible: might be a couple of tokens
                                                             ;; short for one cycle.
                                                             (- n (.availablePermits sem))))
                                                 ^long p
                                                 ^long p
                                                 TimeUnit/MILLISECONDS)}
           spec)))


(defn rate-limit* [{^Semaphore s ::sem
                    to ::wait-timeout-ms :as spec}
                   f]
  (when s
    (when-not (.tryAcquire s
                           to
                           TimeUnit/MILLISECONDS)
      (throw (ex-info "Timeout waiting for rate limiter"
                      {::fb/error ::err/timeout-waiting-for-rate-limiter
                       ::fb/spec spec
                       ::wait-timeout-ms to}))))
  (f))


(defmacro with-rate-limit
  "Evaluates body, guarded by the provided rate limiter."
  [spec & body]
  `(rate-limit* ~spec
                (^{:once true} fn* [] ~@body)))


(defn shutdown [{^ScheduledFuture sf ::sched-fut
                 ^Semaphore s ::sem}]
  (.cancel sf true)
  (.drainPermits s)
  nil)


(defn disable [spec]
  (dissoc spec ::sem))
