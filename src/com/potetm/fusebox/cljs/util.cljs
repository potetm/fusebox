(ns com.potetm.fusebox.cljs.util)


(defn assert-keys [n {req :req-keys :as deets} spec]
  (let [ks' (into #{}
                  (remove (fn [k]
                            (get spec k)))
                  req)]
    (when (seq ks')
      (throw (ex-info (str "Invalid " n)
                      (merge deets
                             {:missing-keys ks'}))))))


(defn pretty-spec
  ([spec]
   (dissoc spec
           :com.potetm.fusebox.cljs.bulkhead/sem
           :com.potetm.fusebox.cljs.circuit-breaker/circuit-breaker
           :com.potetm.fusebox.cljs.circuit-breaker/next-state
           :com.potetm.fusebox.cljs.circuit-breaker/success?
           :com.potetm.fusebox.cljs.memoize/fn
           :com.potetm.fusebox.cljs.fallback/fallback
           :com.potetm.fusebox.cljs.rate-limit/sem
           :com.potetm.fusebox.cljs.rate-limit/interval
           :com.potetm.fusebox.cljs.retry/retry?
           :com.potetm.fusebox.cljs.retry/delay
           :com.potetm.fusebox.cljs.retry/success?)))
