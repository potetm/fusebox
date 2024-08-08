(ns dev.benchmark
  (:require
    [clojure.tools.logging :as log]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [criterium.core :as crit]
    [criterium.stats :as stats])
  (:import
    (io.github.resilience4j.circuitbreaker CallNotPermittedException CircuitBreaker CircuitBreakerConfig)))


(defmacro n-times [times & body]
  `(let [start# (System/nanoTime)]
     (dotimes [_# ~times]
       ~@body)
     (double (/ (- (System/nanoTime)
                   start#)
                ~times))))


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
                            (future (n-times ~n-calls
                                             ~@body))))
                     (range ~n-threads))]
     (into []
           (map deref)
           futs#)))


(defmacro with-r4j-circuit-breaker [^CircuitBreaker cb & body]
  `(try
     (.call (CircuitBreaker/decorateCallable ~cb
                                             (fn []
                                               ~@body)))
     (catch CallNotPermittedException e#
       (log/error "Call not permitted"))))


(defn benchmark:baseline []
  (benchmark 8
             10000
             (+ 1 1)))


(def r4j-circuit-breaker
  (CircuitBreaker/ofDefaults "Test"))


(def fusebox-circuit-breaker
  (cb/init {::cb/next-state (partial cb/next-state:default
                                     {:fail-pct 0.5
                                      :slow-pct 100
                                      :wait-for-count 10
                                      :open->half-open-after-ms 60000})
            ::cb/hist-size 100
            ::cb/half-open-tries 10
            ::cb/slow-call-ms 60000}))


(defmacro benchmark:r4j-circuit-breaker [& body]
  `(benchmark 8
              100000
              (with-r4j-circuit-breaker r4j-circuit-breaker
                ~@body)))


(defmacro benchmark:fusebox [& body]
  `(benchmark 8
              100000
              (cb/with-circuit-breaker fusebox-circuit-breaker
                ~@body)))


(defn zero-wait:always-closed [])


(comment
  (crit/quick-bench (+ 1 1))
  (crit/quick-bench (cb/with-circuit-breaker fusebox-circuit-breaker
                      (+ 1 1)))
  (crit/quick-bench (with-r4j-circuit-breaker r4j-circuit-breaker
                      (+ 1 1)))
  (benchmark:baseline)
  (benchmark:r4j-circuit-breaker)
  (benchmark:fusebox))
