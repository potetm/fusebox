(ns com.potetm.fusebox.cljs.rate-limit
  (:require-macros
    com.potetm.fusebox.cljs.rate-limit)
  (:require
    [com.potetm.fusebox.cljs.semaphore :as sem]
    [com.potetm.fusebox.cljs.util :as util]))

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
    to ::wait-timeout-ms :as spec}]
  (util/assert-keys "Rate Limit"
                    {:req-keys [::bucket-size
                                ::period-ms
                                ::wait-timeout-ms]}
                    spec)
  (let [sem (sem/semaphore n
                           {::bucket-size n
                            ::period-ms p
                            ::wait-timeout-ms to})
        interval (js/setInterval (fn []
                                   (sem/release sem
                                                (- n (.-permits ^sem/Semaphore sem))))
                                 p)]
    (merge {::sem sem
            ::interval interval}
           spec)))


(defn with-rate-limit* [{s ::sem
                         to ::wait-timeout-ms}
                        f]
  (if-not s
    (f)
    (-> (sem/acquire s to)
        (.then (fn []
                 (f))))))


(defn shutdown [{i ::interval}]
  (when i
    (js/clearInterval i)))


(defn disable [spec]
  (dissoc spec ::sem))
