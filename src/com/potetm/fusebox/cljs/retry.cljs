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


(defn with-retry* [{succ? ::success?
                    retry? ::retry?
                    delay ::delay
                    :or {succ? always-success} :as spec}
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
                                      (no (ex-info "fusebox retries exhausted"
                                                   {:com.potetm.fusebox/error :com.potetm.fusebox.error/retries-exhausted
                                                    ::num-tries n
                                                    ::exec-duration-ms ed
                                                    ::val err
                                                    :com.potetm.fusebox/spec (util/pretty-spec spec)}
                                                   err))))))))))]
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
