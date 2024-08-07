(ns com.potetm.fusebox.retry
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.util.concurrent ThreadLocalRandom)))


(set! *warn-on-reflection* true)


(defn init
  "Initialize a retry.

  spec is map containing:
    ::retry?   - A predicate called after an exception to determine
                 whether body should be retried. Takes three args:
                 eval-count, exec-duration-ms, and the exception/failing value.
    ::delay    - A function which calculates the delay in millis to
                 wait prior to the next evaluation. Takes three args:
                 eval-count, exec-duration-ms, and the exception/failing value.
    ::success? - (Optional) A function which takes a return value and determines
                 whether it was successful. If false, body is retried.
                 Defaults to (constantly true)."
  [{_r? ::retry?
    _d ::delay :as spec}]
  (util/assert-keys "Retry"
                    {:req-keys [::retry?
                                ::delay]
                     :opt-keys [::success?]}
                    spec)
  spec)


(def ^{:dynamic true
       :doc
       "The number of times a call has been previously attempted.

       This is intended primarily for diagnostic purposes (e.g. occasional
       logging or metrics) in lieu of callback hooks."}
  *retry-count*)


(def ^{:dynamic true
       :doc
       "The approximate time spent attempting a call.

       This is intended primarily for diagnostic purposes (e.g. occasional
       logging or metrics) in lieu of callback hooks."}
  *exec-duration-ms*)


(def always-success
  (fn [_] true))


(defn retry* [{succ? ::success?
               retry? ::retry?
               delay ::delay
               :or {succ? always-success} :as spec}
              f]
  (if-not retry?
    (f)
    (let [start (System/currentTimeMillis)
          exec-dur #(- (System/currentTimeMillis)
                       start)]
      (loop [n 0]
        (let [[ret v] (util/try-interruptible
                        (binding [*retry-count* n
                                  *exec-duration-ms* (exec-dur)]
                          (let [v (f)]
                            (if (succ? v)
                              [::succ v]
                              [::err v])))
                        (catch Exception e
                          [::err e]))]
          (case ret
            ::succ v
            ::err (let [ed (exec-dur)
                        n' (inc n)]
                    (if (retry? n'
                                ed
                                v)
                      (do (Thread/sleep ^long
                                        (delay n'
                                               ed
                                               v))
                          (recur n'))
                      (throw (ex-info "fusebox retries exhausted"
                                      {::fb/error ::retries-exhausted
                                       ::num-retries n
                                       ::exec-duration-ms ed
                                       ::fb/spec (util/pretty-spec spec)}
                                      v))))))))))


(defn delay-exp
  "Calculate an exponential delay in millis.

  retry-count - the number of previous attempts"
  [retry-count]
  (long (* 100
           (Math/pow 2 retry-count))))


(defn delay-linear
  "Calculate a linear delay in millis.

  factor      - the linear factor to use
  retry-count - the number of previous attempts"
  [factor retry-count]
  (long (* retry-count
           factor)))


(defn jitter
  "Randomly jitter a given delay.

  jitter-factor - the decimal jitter percentage, between 0 and 1
  delay         - the base delay in millis"
  [jitter-factor delay]
  (let [jit (long (* jitter-factor
                     delay))]
    (+ delay
       (.nextLong (ThreadLocalRandom/current)
                  (- jit)
                  jit))))


(defmacro with-retry
  "Evaluates body, retrying according to the provided retry spec."
  [spec & body]
  `(retry* ~spec
           (fn [] ~@body)))


(defn shutdown [spec])
