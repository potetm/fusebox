(ns dev.benchmark
  (:require
    [clojure.tools.logging :as log]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [com.potetm.fusebox.rate-limit :as rl]
    [com.potetm.fusebox.retry :as retry]
    [criterium.core :as crit]
    [criterium.stats :as stats])
  (:import
    (io.github.resilience4j.circuitbreaker CallNotPermittedException
                                           CircuitBreaker
                                           CircuitBreakerConfig)))


(defmacro n-times [times & body]
  `(let [start# (System/nanoTime)]
     (dotimes [_# ~times]
       ~@body)
     (double (/ (- (System/nanoTime)
                   start#)
                ~times))))


(defmacro n-times:all [times & body]
  `(into []
         (map (fn [_#]
                (let [start# (System/nanoTime)]
                  ~@body
                  (- (System/nanoTime)
                     start#))))
         (range ~times)))


(defmacro n-times-ms [times & body]
  `(let [start# (System/currentTimeMillis)]
     (dotimes [_# ~times]
       ~@body)
     (double (/ (- (System/currentTimeMillis)
                   start#)
                ~times))))


(defmacro benchmark [n-threads n-calls & body]
  `(let [futs# (into []
                     (map (fn [_#]
                            (future (n-times:all ~n-calls
                                      ~@body))))
                     (range ~n-threads))]
     (double (/ (transduce (mapcat deref)
                           +
                           0
                           futs#)
                (* ~n-threads ~n-calls)))))


(defmacro with-r4j-circuit-breaker [^CircuitBreaker cb & body]
  `(try
     (.call (CircuitBreaker/decorateCallable ~cb
                                             (fn []
                                               ~@body)))
     (catch CallNotPermittedException e#
       (log/error "Call not permitted"))))


(defmacro benchmark:baseline [& body]
  `(benchmark 8
              1000000
              ~@body))


(def r4j-circuit-breaker
  (CircuitBreaker/ofDefaults "Test"))


(def fusebox-circuit-breaker
  (cb/init {::cb/next-state #(cb/next-state:default {:fail-pct 0.5
                                                     :slow-pct 100
                                                     :wait-for-count 10
                                                     :open->half-open-after-ms 60000}
                                                    %)
            ::cb/hist-size 100
            ::cb/half-open-tries 10
            ::cb/slow-call-ms 60000}))


(def rate-limiter
  (rl/init {::rl/bucket-size 1000000
            ::rl/period-ms 1
            ::rl/wait-timeout-ms 5000}))


(def retry
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 3))
               ::retry/delay (constantly 500)}))

(defmacro benchmark:r4j-circuit-breaker [& body]
  `(benchmark 8
              1000000
              (with-r4j-circuit-breaker r4j-circuit-breaker
                ~@body)))


(defmacro benchmark:fusebox [& body]
  `(benchmark 8
              1000000
              (cb/with-circuit-breaker fusebox-circuit-breaker
                ~@body)))


(defn zero-wait:always-closed [])


(comment
  (crit/quick-bench (+ 1 1))
  (crit/quick-bench (cb/with-circuit-breaker fusebox-circuit-breaker
                      (+ 1 1)))
  (crit/quick-bench (with-r4j-circuit-breaker r4j-circuit-breaker
                      (+ 1 1)))

  (n-times 100000
    (with-r4j-circuit-breaker r4j-circuit-breaker
      (+ 1 1)))

  (n-times 100000
    (cb/with-circuit-breaker fusebox-circuit-breaker
      (+ 1 1)))


  (n-times 10000000
    (retry/with-retry retry
      (+ 1 1)))
  (benchmark:baseline
    (+ 1 1))
  (benchmark:r4j-circuit-breaker
    (+ 1 1))
  fusebox-circuit-breaker
  123
  (benchmark:fusebox
    (+ 1 1)))
