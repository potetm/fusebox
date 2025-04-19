(ns com.potetm.fusebox.retry
  (:require
    [clojure.math :as math]
    [clojure.tools.logging :as log]
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.error :as-alias err]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ThreadLocalRandom)))


(set! *warn-on-reflection* true)


(defn init
  "Initialize a retry.

  spec is map containing:
    ::retry?    - A predicate called after an exception to determine
                  whether body should be retried. Takes three args:
                  eval-count, exec-duration-ms, and the exception/failing value.
    ::delay     - A function which calculates the delay in millis to
                  wait prior to the next evaluation. Takes three args:
                  eval-count, exec-duration-ms, and the exception/failing value.
    ::success?  - (Optional) A function which takes a return value and determines
                  whether it was successful. If false, body is retried. The last
                  failing value can be found under the `::retry/val` key in the
                  thrown ex-info's data. Defaults to (constantly true).
    ::exception - (Optional) A function which returns the exception to throw once
                  ::retry? returns false. Defaults to an ExceptionInfo with fusebox
                  ex-data and the last exception as the cause. See also
                  `wrap-ex-after-retry and `no-wrap-ex. Takes three args:
                  eval-count, exec-duration-ms, and the exception/failing value."
  [{_r? ::retry?
    _d ::delay :as spec}]
  (util/assert-keys "Retry"
                    {:req-keys [::retry?
                                ::delay]
                     :opt-keys [::success?]}
                    spec)
  spec)


(def always-success
  (fn [_] true))


(defn default-ex
  "The default ::exception fn. Always returns an ExceptionInfo with fusebox
  ex-data and the last exception as the cause."
  [n ed v]
  (let [cause (when (instance? Throwable v)
                v)]
    (ex-info "fusebox retries exhausted"
             (cond-> {::fb/error ::err/retries-exhausted
                      ::num-retries n
                      ::exec-duration-ms ed}
               ;; Attach last return value only if it's not an exception
               (not cause) (assoc ::val v))
             cause)))


(defn wrap-ex-after-retry
  "An ::exception fn. Only wraps with an ExceptionInfo if a retry was attempted
  or if the failing value was not an Exception (i.e. ::success? returned false)."
  [n ed v]
  (if (and (zero? n)
           (instance? Throwable v))
    v
    (default-ex n ed v)))


(defn no-wrap-ex
  "An ::exception fn. Only wraps with an if the failing value was not an
  Exception (i.e. ::success? returned false)."
  [n ed v]
  (if (instance? Throwable v)
    v
    (default-ex n ed v)))


(defn with-retry* [{succ? ::success?
                    retry? ::retry?
                    delay ::delay
                    exception ::exception
                    :or {succ? always-success
                         exception default-ex}}
                   f]
  (if-not retry?
    (f nil nil)
    (let [start (System/currentTimeMillis)
          exec-dur #(- (System/currentTimeMillis)
                       start)]
      (loop [n 0]
        (let [[ret v] (util/try-interruptible
                        (let [v (f n (exec-dur))]
                          (if (succ? v)
                            [::succ v]
                            [::err v]))
                        (catch Exception e
                          [::err e]))]
          (case ret
            ::succ v
            ::err (let [ed (exec-dur)
                        n' (inc n)]
                    (if (retry? n'
                                ed
                                v)
                      (let [d (delay n'
                                     ed
                                     v)]
                        (log/info "fusebox retrying"
                                  {:count n'
                                   :exec-duration ed
                                   :delay-ms d})
                        (Thread/sleep ^long d)
                        (recur n'))
                      (throw (exception n ed v))))))))))


(defn ^:deprecated retry*
  "DEPRECATED: This was an early mistake. It should have been named with-retry*."
  [spec f]
  (with-retry* spec f))

(defn delay-exp
  "Calculate an exponential delay in millis.

  base        - the base number to scale (default 100)
  retry-count - the number of previous attempts"
  ^long ([retry-count]
         (long (math/scalb 100 retry-count)))
  ^long ([base retry-count]
         (long (math/scalb base retry-count))))


(defn delay-linear
  "Calculate a linear delay in millis.

  factor      - the linear factor to use
  retry-count - the number of previous attempts"
  ^long [factor retry-count]
  (long (* retry-count
           factor)))


(defn jitter
  "Randomly jitter a given delay.

  jitter-factor - the decimal jitter percentage, between 0 and 1
  delay         - the base delay in millis"
  ^long [jitter-factor delay]
  (let [jit (long (* jitter-factor
                     delay))]
    (+ delay
       (.nextLong (ThreadLocalRandom/current)
                  (- jit)
                  jit))))


(defmacro with-retry
  "Evaluates body, retrying according to the provided retry spec."
  [bindings|spec & [spec|body & body :as b]]
  (if (vector? bindings|spec)
    `(with-retry* ~spec|body
                  (fn ~bindings|spec ~@body))
    `(with-retry* ~bindings|spec
                  (fn [count# duration#] ~@b))))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::retry?))
