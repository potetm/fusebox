(ns com.potetm.fusebox.cljs.retry
  (:require-macros
    com.potetm.fusebox.cljs.retry)
  (:require
    [clojure.math :as math]
    [com.potetm.fusebox.cljs.util :as util]))


(def always-success
  (fn [_] true))


(defn resolve-after [ms]
  (js/Promise. (fn [yes no]
                 (js/setTimeout yes ms))))


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


(defn default-ex
  "The default ::exception fn. Always returns an ExceptionInfo with fusebox
  ex-data and the last exception as the cause."
  [n ed v]
  (ex-info "fusebox retries exhausted"
           {:com.potetm.fusebox/error :com.potetm.fusebox.error/retries-exhausted
            ::num-tries n
            ::exec-duration-ms ed
            ::val v}
           v))


(defn wrap-ex-after-retry
  "An ::exception fn. Only wraps with an ExceptionInfo if a retry was attempted
  or if the failing value was not an Exception (i.e. ::success? returned false)."
  [n ed v]
  (if (and (zero? n)
           (instance? js/Error v))
    v
    (default-ex n ed v)))


(defn no-wrap-ex
  "An ::exception fn. Only wraps with an if the failing value was not an
  Exception (i.e. ::success? returned false)."
  [n ed v]
  (if (instance? js/Error v)
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
    (let [start (.getTime (js/Date.))
          exec-dur #(- (.getTime (js/Date.))
                       start)
          run (fn run [n]
                (js/Promise.
                  (fn [yes no]
                    (-> (f n (exec-dur))
                        (.then (fn [v]
                                 (if (succ? v)
                                   (yes v)
                                   (throw v))))
                        (.catch (fn [err]
                                  (let [n' (inc n)
                                        ed (exec-dur)]
                                    (if (retry? n'
                                                ed
                                                err)
                                      (-> (resolve-after (delay n' ed err))
                                          (.then #(run (inc n)))
                                          (.then yes)
                                          (.catch no))
                                      (no (exception n ed err))))))))))]
      (run 0))))


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
       (- (* (math/random)
             (* 2 jit))
          jit))))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::retry?))
