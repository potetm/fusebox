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


(defn rate-limiter [{n ::bucket-size
                     p ::period-ms :as opts}]
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
    (merge opts
           {::sem sem
            ::bg-exec (doto exec
                        (.submit ^Runnable
                                 (fn []
                                   (while true
                                     (Thread/sleep ^long p)
                                     (.release sem
                                               ;; This isn't atomic, but the worst case
                                               ;; isn't terrible: might be a couple of tokens
                                               ;; short for one cycle.
                                               (- n (.availablePermits sem)))))))})))


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


(defmacro with-rate-limit [spec & body]
  `(rate-limit* ~spec
                (^{:once true} fn* [] ~@body)))


(defn shutdown [{^ExecutorService bg ::bg-exec}]
  (when (not= bg util/virtual-exec)
    (.shutdownNow bg)))


(comment
  (def rl (rate-limiter {::bucket-size 2
                         ::period-ms 100
                         ::wait-timeout-ms 500}))
  (shutdown rl)
  )
