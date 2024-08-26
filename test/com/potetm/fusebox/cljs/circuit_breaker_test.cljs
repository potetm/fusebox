(ns com.potetm.fusebox.cljs.circuit-breaker-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.circuit-breaker :as cb]
    [com.potetm.promise :as p]))


(defn swallow-ex [p]
  (.catch p (fn [v])))


(deftest circuit-breaker-test
  (testing "it works"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.5
                                                   :slow-pct 0.5
                                                   :wait-for-count 3
                                                   :open->half-open-after-ms 100})
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 100})]
        (p/await [_ (p/all-settled (into []
                                         (map (fn [_]
                                                (cb/with-circuit-breaker cb
                                                  (p/resolve (+ 1 1)))))
                                         (range 3)))
                  _ (p/all-settled (into []
                                         (map (fn [_]
                                                (swallow-ex
                                                  (cb/with-circuit-breaker cb
                                                    (p/wrap #(throw (ex-info "" {})))))
                                                ))
                                         (range 4)))]
          (is (thrown-with-msg? ExceptionInfo
                                #"fusebox circuit breaker open"
                                (cb/with-circuit-breaker cb
                                  (p/resolve (+ 1 1)))))

          (p/await [_ (p/timeout 100)
                    _ (swallow-ex (cb/with-circuit-breaker cb
                                    (p/wrap #(throw (ex-info "" {})))))]
            (is (= ::cb/half-opened
                   (.-state (cb/current cb))))
            (p/await [_ (swallow-ex (cb/with-circuit-breaker cb
                                      (p/wrap #(throw (ex-info "" {})))))]
              (is (= ::cb/half-opened
                     (.-state (cb/current cb))))
              (p/await [ret (cb/with-circuit-breaker cb
                              (p/resolve (+ 1 1)))]
                (is (= 2 ret))
                ;; open now because 2/3 half-open calls failed
                (is (= ::cb/opened
                       (.-state (cb/current cb))))
                (is (thrown-with-msg? ExceptionInfo
                                      #"fusebox circuit breaker open"
                                      (cb/with-circuit-breaker cb
                                        (+ 1 1))))
                (p/await [_ (p/timeout 100)
                          ret (cb/with-circuit-breaker cb
                                (p/resolve (+ 1 1)))]
                  (is (= 2 ret))
                  (is (= ::cb/half-opened
                         (.-state (cb/current cb))))
                  (p/await [ret (p/all (into []
                                             (map (fn [_]
                                                    (cb/with-circuit-breaker cb
                                                      (p/resolve (+ 1 1)))))
                                             (range 2)))]
                    (is (= (vec ret) [2 2]))
                    (is (= ::cb/closed
                           (.-state (cb/current cb))))
                    (done)))))))))))


(deftest half-open-tries-min
  (testing "half-open wait-for-count will min with ::cb/half-open-tries"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.5
                                                   :slow-pct 0.5
                                                   :wait-for-count 10
                                                   :open->half-open-after-ms 100})
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 100})]
        (p/await [_ (p/all (into []
                                 (map (fn [_]
                                        (cb/with-circuit-breaker cb
                                          (p/resolve (+ 1 1)))))
                                 (range 10)))
                  _ (p/all (into []
                                 (map (fn [_]
                                        (swallow-ex (cb/with-circuit-breaker cb
                                                      (p/wrap #(throw (ex-info "" {})))))))
                                 (range 6)))]
          (is (thrown-with-msg? ExceptionInfo
                                #"fusebox circuit breaker open"
                                (cb/with-circuit-breaker cb
                                  (+ 1 1))))
          (p/await [_ (p/timeout 100)
                    _ (cb/with-circuit-breaker cb
                        (p/resolve (+ 1 1)))]
            (is (= ::cb/half-opened
                   (.-state (cb/current cb))))
            (p/await [_ (p/all (into []
                                     (map (fn [_]
                                            (cb/with-circuit-breaker cb
                                              (p/resolve (+ 1 1)))))
                                     (range 2)))]
              (is (= ::cb/closed
                     (.-state (cb/current cb))))

              (done))))))))


(deftest fail-pct-equals-test
  (testing "half-open fail-pct equals actual fail pct"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.5
                                                   :slow-pct 0.5
                                                   :wait-for-count 10
                                                   :open->half-open-after-ms 100})
                         ::cb/hist-size 10
                         ::cb/half-open-tries 10
                         ::cb/slow-call-ms 100})]
        (p/await [_ (p/all (into []
                                 (map (fn [_]
                                        (cb/with-circuit-breaker cb
                                          (p/resolve (+ 1 1)))))
                                 (range 10)))
                  _ (p/all (into []
                                 (map (fn [_]
                                        (swallow-ex (cb/with-circuit-breaker cb
                                                      (p/wrap #(throw (ex-info "" {})))))))
                                 (range 6)))]
          (is (thrown-with-msg? ExceptionInfo
                                #"fusebox circuit breaker open"
                                (cb/with-circuit-breaker cb
                                  (+ 1 1))))
          (p/await [_ (p/timeout 100)
                    _ (cb/with-circuit-breaker cb
                        (p/resolve (+ 1 1)))]
            (is (= ::cb/half-opened
                   (.-state (cb/current cb))))
            (p/await [_ (p/all (into []
                                     (map (fn [_]
                                            (cb/with-circuit-breaker cb
                                              (p/resolve (+ 1 1)))))
                                     (range 4)))
                      _ (p/all (into []
                                     (map (fn [_]
                                            (swallow-ex (cb/with-circuit-breaker cb
                                                          (p/wrap #(throw (ex-info "" {})))))))
                                     (range 5)))]
              (is (= ::cb/closed
                     (.-state (cb/current cb))))
              (done))))))))


(deftest success-fn
  (testing "::success fn"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.4
                                                   :slow-pct 0.4
                                                   :wait-for-count 3
                                                   :open->half-open-after-ms 100})
                         ::cb/success? even?
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 100})]
        (p/await [_ (p/all (into []
                                 (map (fn [i]
                                        (cb/with-circuit-breaker cb
                                          (p/resolve (inc i)))))
                                 (range 3)))]
          (is (thrown-with-msg? ExceptionInfo
                                #"fusebox circuit breaker open"
                                (cb/with-circuit-breaker cb
                                  (+ 1 1))))
          (done))))))



(deftest slow-calls
  (testing "slow calls"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.4
                                                   :slow-pct 0.4
                                                   :wait-for-count 3
                                                   :open->half-open-after-ms 100})
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 10})]
        (p/await [_ (p/all (into []
                                 (map (fn [i]
                                        (cb/with-circuit-breaker cb
                                          (if (even? i)
                                            (p/timeout 20)
                                            (p/resolve :done)))))
                                 (range 3)))]
          (is (thrown-with-msg? ExceptionInfo
                                #"fusebox circuit breaker open"
                                (cb/with-circuit-breaker cb
                                  (p/resolve (+ 1 1)))))
          (done))))))


(deftest disabled
  (testing "disabled"
    (async done
      (let [cb (cb/init {::cb/next-state (constantly nil)
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 10})]
        (p/await [_ (p/all (into []
                                 (map (fn [_]
                                        (swallow-ex
                                          (cb/with-circuit-breaker cb
                                            (p/wrap #(throw (ex-info "" {})))))))
                                 (range 20)))
                  res (cb/with-circuit-breaker cb
                        (p/resolve (+ 1 1)))]
          (is (= 2 res))
          (done))))))


(deftest noop
  (testing "noop"
    (async done
      (p/await [res (cb/with-circuit-breaker {:something 'else}
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest nil-test
  (testing "nil"
    (async done
      (p/await [res (cb/with-circuit-breaker nil
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (cb/init {::cb/hist-size 100})))))



(deftest disable-test
  (testing "disable"
    (async done
      (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                  {:fail-pct 0.5
                                                   :slow-pct 0.5
                                                   :wait-for-count 3
                                                   :open->half-open-after-ms 100})
                         ::cb/hist-size 10
                         ::cb/half-open-tries 3
                         ::cb/slow-call-ms 100})]
        (p/await [_ (p/all (into []
                                 (map (fn [i]
                                        (swallow-ex (cb/with-circuit-breaker (cb/disable cb)
                                                      (p/wrap #(throw (ex-info "" {})))))))
                                 (range 10)))
                  res1 (cb/with-circuit-breaker (cb/disable cb)
                         (p/resolve (+ 1 1)))
                  res2 (cb/with-circuit-breaker cb
                         (p/resolve (+ 1 1)))]
          (is (= 2 res1))
          (is (= 2 res2))
          (is (= 1 (.-total-count (cb/current cb))))
          (done))))))
