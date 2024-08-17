(ns dev.resilience4j
  (:require
    [clojure.tools.logging :as log])
  (:import
    (io.github.resilience4j.bulkhead Bulkhead)
    (io.github.resilience4j.circuitbreaker CallNotPermittedException
                                           CircuitBreaker)
    (io.github.resilience4j.ratelimiter RateLimiter RateLimiterConfig)
    (io.github.resilience4j.ratelimiter.internal SemaphoreBasedRateLimiter)
    (io.github.resilience4j.retry Retry)
    (io.github.resilience4j.timelimiter TimeLimiter)
    (java.time Duration)
    (java.util.concurrent CompletableFuture)
    (java.util.function Supplier)))


(set! *warn-on-reflection* true)

(defn circuit-breaker []
  (CircuitBreaker/ofDefaults "Test"))


(defmacro with-circuit-breaker [^CircuitBreaker cb & body]
  `(try
     (.call (CircuitBreaker/decorateCallable ~cb
                                             (fn []
                                               ~@body)))
     (catch CallNotPermittedException e#
       (log/error "Call not permitted"))))


(defn bulkhead []
  (Bulkhead/ofDefaults "Test"))


(defmacro with-bulkhead [^Bulkhead bh & body]
  `(try
     (.call (Bulkhead/decorateCallable ~bh
                                       (fn []
                                         ~@body)))
     (catch CallNotPermittedException e#
       (log/error "Call not permitted"))))


(defn rate-limiter []
  (SemaphoreBasedRateLimiter. "Test"
                              (-> (RateLimiterConfig/custom)
                                  (.limitForPeriod 1000000)
                                  (.limitRefreshPeriod (Duration/ofMillis 1))
                                  (.timeoutDuration (Duration/ofSeconds 5))
                                  (.build))))


(defmacro with-rate-limiter [^RateLimiter rl & body]
  `(try
     (.call (RateLimiter/decorateCallable ~rl
                                          (fn []
                                            ~@body)))
     (catch Exception e#
       (log/error e# "Error in Rate Limiter"))))


(defn retry []
  (Retry/ofDefaults "Test"))


(defmacro with-retry [^Retry retry & body]
  `(try
     (.call (Retry/decorateCallable ~retry
                                    (fn []
                                      ~@body)))
     (catch Exception e#
       (log/error e# "Error in Retry"))))


(defn timeout ^TimeLimiter []
  (TimeLimiter/ofDefaults "Test"))


(defmacro with-timeout [^TimeLimiter to & body]
  `(try
     (.executeFutureSupplier ~to
                             (reify Supplier
                               (get [this]
                                 (CompletableFuture/supplyAsync (reify Supplier
                                                                  (get [this]
                                                                    ~@body))))))
     (catch Exception e#
       (log/error e# "Error in Timeout"))))

