(ns com.potetm.fusebox.circuit-breaker-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.circuit-breaker :as cb])
  (:import
    (clojure.lang ExceptionInfo)))


(defmacro swallow-ex [& body]
  `(try
     ~@body
     (catch Exception e#)))


(deftest circuit-breaker-test
  (testing "it works"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.5
                                                 :slow-pct 0.5
                                                 :wait-for-count 3
                                                 :open->half-open-after-ms 100})
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 100})]
      (dotimes [_ 3]
        (cb/with-circuit-breaker cb
          (+ 1 1)))
      (dotimes [_ 4]
        (swallow-ex (cb/with-circuit-breaker cb
                      (throw (ex-info "" {})))))

      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))
      (Thread/sleep 100)
      (swallow-ex (cb/with-circuit-breaker cb
                    (throw (ex-info "" {}))))
      (is (= ::cb/half-opened
             (.-state (cb/current cb))))
      (swallow-ex (cb/with-circuit-breaker cb
                    (throw (ex-info "" {}))))
      (is (= ::cb/half-opened
             (.-state (cb/current cb))))
      (is (= 2
             (cb/with-circuit-breaker cb
               (+ 1 1))))
      ;; open now because 2/3 half-open calls failed
      (is (= ::cb/opened
             (.-state (cb/current cb))))
      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))
      (Thread/sleep 100)
      (is (= 2
             (cb/with-circuit-breaker cb
               (+ 1 1))))
      (is (= ::cb/half-opened
             (.-state (cb/current cb))))
      (dotimes [_ 2]
        (is (= 2
               (cb/with-circuit-breaker cb
                 (+ 1 1)))))
      (is (= ::cb/closed
             (.-state (cb/current cb))))))

  (testing "half-open wait-for-count will min with ::cb/half-open-tries"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.5
                                                 :slow-pct 0.5
                                                 :wait-for-count 10
                                                 :open->half-open-after-ms 100})
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 100})]
      (dotimes [_ 10]
        (cb/with-circuit-breaker cb
          (+ 1 1)))

      (dotimes [_ 6]
        (swallow-ex (cb/with-circuit-breaker cb
                      (throw (ex-info "" {})))))

      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))
      (Thread/sleep 100)

      (cb/with-circuit-breaker cb
        (+ 1 1))

      (is (= ::cb/half-opened
             (.-state (cb/current cb))))

      (dotimes [_ 2]
        (cb/with-circuit-breaker cb
          (+ 1 1)))

      (is (= ::cb/closed
             (.-state (cb/current cb))))))


  (testing "half-open fail-pct equals actual fail pct"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.5
                                                 :slow-pct 0.5
                                                 :wait-for-count 10
                                                 :open->half-open-after-ms 100})
                       ::cb/hist-size 10
                       ::cb/half-open-tries 10
                       ::cb/slow-call-ms 100})]
      (dotimes [_ 10]
        (cb/with-circuit-breaker cb
          (+ 1 1)))

      (dotimes [_ 6]
        (swallow-ex (cb/with-circuit-breaker cb
                      (throw (ex-info "" {})))))

      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))
      (Thread/sleep 100)

      (cb/with-circuit-breaker cb
        (+ 1 1))

      (is (= ::cb/half-opened
             (.-state (cb/current cb))))

      (dotimes [_ 4]
        (cb/with-circuit-breaker cb
          (+ 1 1)))

      (dotimes [_ 5]
        (swallow-ex (cb/with-circuit-breaker cb
                      (throw (ex-info "" {})))))

      (is (= ::cb/closed
             (.-state (cb/current cb))))))

  (testing "::success fn"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.4
                                                 :slow-pct 0.4
                                                 :wait-for-count 3
                                                 :open->half-open-after-ms 100})
                       ::cb/success? even?
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 100})]
      (dotimes [i 3]
        (cb/with-circuit-breaker cb
          (inc i)))

      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))))

  (testing "slow calls"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.4
                                                 :slow-pct 0.4
                                                 :wait-for-count 3
                                                 :open->half-open-after-ms 100})
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 10})]
      (dotimes [i 3]
        (cb/with-circuit-breaker cb
          (when (even? i)
            (Thread/sleep 15))))

      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))))

  (testing "disabled"
    (let [cb (cb/init {::cb/next-state (constantly nil)
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 10})]
      (dotimes [i 20]
        (swallow-ex
          (cb/with-circuit-breaker cb
            (throw (ex-info "" {})))))

      (is (= 2 (cb/with-circuit-breaker cb
                 (+ 1 1))))))

  (testing "noop"
    (is (= 123
           (cb/with-circuit-breaker {:something 'else}
             123)))

    (is (= 123
           (cb/with-circuit-breaker nil
             123))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (cb/init {::cb/hist-size 100}))))

  (testing "disable"
    (let [cb (cb/init {::cb/next-state (partial cb/next-state:default
                                                {:fail-pct 0.5
                                                 :slow-pct 0.5
                                                 :wait-for-count 3
                                                 :open->half-open-after-ms 100})
                       ::cb/hist-size 10
                       ::cb/half-open-tries 3
                       ::cb/slow-call-ms 100})]
      (dotimes [_ 10]
        (swallow-ex (cb/with-circuit-breaker (cb/disable cb)
                      (throw (ex-info "" {})))))

      (is (= 2 (cb/with-circuit-breaker (cb/disable cb)
                 (+ 1 1))))

      (is (= 2 (cb/with-circuit-breaker cb
                 (+ 1 1))))

      (is (= 1 (.-total-count (cb/current cb)))))))



(comment
  (def cb (cb/init {::cb/next-state (partial cb/next-state:default
                                             {:fail-pct 0.4
                                              :slow-pct 0.4
                                              :wait-for-count 3
                                              :open->half-open-after-ms 100})
                    ::cb/success? even?
                    ::cb/hist-size 10
                    ::cb/half-open-tries 3
                    ::cb/slow-call-ms 100}))
  (dotimes [i 3]
    (cb/with-circuit-breaker cb
      (inc i)))

  cb


  (is (thrown-with-msg? ExceptionInfo
                        #"fusebox circuit breaker open"
                        (cb/with-circuit-breaker cb
                          (+ 1 1)))))
