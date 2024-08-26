(ns com.potetm.fusebox.cljs.retry
  (:require-macros
    com.potetm.fusebox.cljs.retry)
  (:require
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
                                                    :com.potetm.fusebox/spec (util/pretty-spec spec)}
                                                   err))))))))))]
      (run 0))))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::retry?))
