(ns dev.circuit-breaker
  (:require
    [clojure.tools.logging :as log]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [com.potetm.fusebox.retry :as retry])
  (:import (clojure.lang ExceptionInfo)))

(defn circuit-breaker []
  (cb/init {::cb/next-state #(cb/next-state:default {:fail-pct 0.5
                                                     :slow-pct 1
                                                     :wait-for-count 10
                                                     :open->half-open-after-ms 60000}
                                                    %)
            ::cb/hist-size 100
            ::cb/half-open-tries 10
            ::cb/slow-call-ms 60000}))


(def error-rate
  (atom 0))


(defn run [cb sleep-time]
  (try
    (cb/with-circuit-breaker cb
      (log/info "Circuit breaker allowed"
                (dissoc (cb/current cb)
                        :record))
      (when (< (rand) @error-rate)
        (throw (ex-info "ERROR!" {}))))
    (catch ExceptionInfo e
      (log/info "Circuit breaker error"
                (ex-data e))))
  (Thread/sleep (retry/jitter 0.2 sleep-time)))


(defn start [cb n sleep-time]
  (into []
        (map (fn [_]
               (future (while true
                         (run cb sleep-time)))))
        (range n)))


(defn cancel [futs]
  (doseq [f futs]
    (future-cancel f)))


(comment
  (def cb (circuit-breaker))
  (def futs (start cb 20 1000))

  (reset! error-rate 0.5)
  (cb/current cb)

  (cb/with-circuit-breaker cb
    (+ 1 1))


  (cancel futs)

  )
