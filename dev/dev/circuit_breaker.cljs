(ns dev.circuit-breaker
  (:require
    [com.potetm.fusebox.cljs.circuit-breaker :as cb]
    [com.potetm.fusebox.cljs.retry :as retry]))


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
  (js/setInterval (fn []
                    (-> (cb/with-circuit-breaker cb
                          (js/Promise.
                            (fn [yes no]
                              (js/console.log "Circuit breaker allowed"
                                              (name (.-state (cb/current cb))))
                              (when (< (rand) @error-rate)
                                (throw (ex-info "ERROR!" {})))
                              (yes :yes!))))
                        (.catch (fn [e]
                                  (js/console.log "Circuit breaker error"
                                                  (name (.-state (cb/current cb))))))))
                  (retry/jitter 0.2 sleep-time)))


(defn start [cb n sleep-time]
  (into []
        (map #(run cb sleep-time))
        (range n)))


(defn stop [invs]
  (doseq [i invs]
    (js/clearInterval i)))


(comment
  (def cb (circuit-breaker))
  (def invs (start cb 10 4000))
  cb
  (reset! error-rate 0.30)

  (stop invs)
  )
