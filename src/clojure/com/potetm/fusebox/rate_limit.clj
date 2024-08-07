(ns com.potetm.fusebox.rate-limit
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ExecutorService
                          Executors
                          Semaphore
                          ThreadFactory
                          TimeUnit)))


(set! *warn-on-reflection* true)


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
  (let [sem (Semaphore. n)
        ;; Wanted to use a raw thread here, but the executor service syntax
        ;; lets us use eval without local bindings. Should be no functional
        ;; difference, and still lets us use Virtual Threads when available.
        ^ExecutorService exec
        (or util/virtual-exec
            (Executors/newSingleThreadExecutor
              (reify ThreadFactory
                (newThread [this r]
                  (doto (Thread. ^Runnable r)
                    (.setName "rate-limiter-bg-thread")
                    (.setDaemon true))))))]
    (merge {::sem sem
            ::bg-exec (doto exec
                        (.submit ^Runnable
                                 (fn []
                                   (while true
                                     (Thread/sleep ^long p)
                                     (.release sem
                                               ;; This isn't atomic, but the worst case
                                               ;; isn't terrible: might be a couple of tokens
                                               ;; short for one cycle.
                                               (- n (.availablePermits sem)))))))}
           spec)))


(defn rate-limit* [{^Semaphore s ::sem
                    to ::wait-timeout-ms :as spec}
                   f]
  (when s
    (when-not (.tryAcquire s
                           to
                           TimeUnit/MILLISECONDS)
      (throw (ex-info "Timeout waiting for rate limiter"
                      {::fb/error ::timeout-waiting-for-rate-limiter
                       ::fb/spec spec
                       ::wait-timeout-ms to}))))
  (f))


(defmacro with-rate-limit
  "Evaluates body, guarded by the provided rate limiter."
  [spec & body]
  `(rate-limit* ~spec
                (^{:once true} fn* [] ~@body)))


(defn shutdown [{^ExecutorService bg ::bg-exec
                 ^Semaphore s ::sem}]
  (when (not= bg util/virtual-exec)
    (.shutdownNow bg))
  (.drainPermits s)
  nil)


(comment
  (def rl (init {::bucket-size 2
                 ::period-ms 100
                 ::wait-timeout-ms 500}))
  (shutdown rl)
  )
