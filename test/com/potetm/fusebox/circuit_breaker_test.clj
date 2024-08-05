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
    (let [cb (cb/circuit-breaker {::cb/next-state (partial cb/next-state:default
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
      (dotimes [_ 2]
        (swallow-ex (cb/with-circuit-breaker cb
                      (throw (ex-info "" {})))))
      (cb/with-circuit-breaker cb
        (+ 1 1))
      (is (thrown-with-msg? ExceptionInfo
                            #"fusebox circuit breaker open"
                            (cb/with-circuit-breaker cb
                              (+ 1 1))))))

  (testing "::success fn"
    (let [cb (cb/circuit-breaker {::cb/next-state (partial cb/next-state:default
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
    (let [cb (cb/circuit-breaker {::cb/next-state (partial cb/next-state:default
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
    (let [cb (cb/circuit-breaker {::cb/next-state (constantly nil)
                                  ::cb/hist-size 10
                                  ::cb/half-open-tries 3
                                  ::cb/slow-call-ms 10})]
      (dotimes [i 20]
        (swallow-ex
          (cb/with-circuit-breaker cb
            (throw (ex-info "" {})))))

      (is (= 2 (cb/with-circuit-breaker cb
                 (+ 1 1))))))

  (testing "force opened"
    (let [cb (cb/circuit-breaker {::cb/next-state (constantly nil)
                                  :state :opened
                                  ::cb/hist-size 10
                                  ::cb/half-open-tries 3
                                  ::cb/slow-call-ms 10})]
      (dotimes [i 20]
        (is (thrown? ExceptionInfo
                     (cb/with-circuit-breaker cb
                       (inc i))))))))


(comment
  @(def cb (cb/circuit-breaker {::cb/next-state (partial cb/next-state:default
                                                         {:fail-pct 0.5
                                                          :slow-pct 0.5
                                                          :wait-for-count 3
                                                          :open->half-open-after-ms 100})
                                ::cb/success? odd?
                                ::cb/hist-size 10
                                ::cb/half-open-tries 3
                                ::cb/slow-call-ms 100}))
  (cb/with-circuit-breaker cb
    )

  (dotimes [i 4]
    (cb/with-circuit-breaker cb
      (inc i)))
  @(::cb/circuit-breaker cb)

  (dotimes [_ 3]
    (cb/with-circuit-breaker cb
      (+ 1 1)))
  (dotimes [_ 6]
    (swallow-ex (cb/with-circuit-breaker cb
                  (throw (ex-info "" {})))))

  cb
  (do (cb/with-circuit-breaker cb
        (+ 1 1))
      ))
