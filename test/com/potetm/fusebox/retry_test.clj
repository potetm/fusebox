(ns com.potetm.fusebox.retry-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.retry :as retry])
  (:import
    (clojure.lang ExceptionInfo)))


(defmacro timing [& body]
  `(let [start# (System/currentTimeMillis)
         ret# (do ~@body)
         end# (System/currentTimeMillis)]
     [(- end# start#) ret#]))


(deftest retry-test
  (testing "base case"
    (let [invokes-count (atom 0)
          ret (try
                (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                                (< n 10))
                                               ::retry/delay (constantly 1)})
                  (swap! invokes-count inc)
                  (throw (ex-info "" {})))
                (catch ExceptionInfo ei
                  ::fail))]
      (is (= ret ::fail))
      (is (= 10 @invokes-count))))


  (testing "base case - eventual success"
    (let [invokes-count (atom 0)
          ret (try
                (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                                (< n 10))
                                               ::retry/delay (constantly 1)})

                  (if (= 5 (swap! invokes-count inc))
                    ::success
                    (throw (ex-info "" {}))))
                (catch ExceptionInfo ei
                  ::fail))]
      (is (= ret ::success))))


  (testing "Delaying"
    (let [invokes-count (atom 0)
          [ms ret] (timing
                     (retry/with-retry (retry/init {::retry/retry? (constantly true)
                                                    ::retry/delay (constantly 100)})
                       (when (= 1 (swap! invokes-count inc))
                         (throw (ex-info "" {})))
                       ::success))]
      (is (<= 100 ms))
      (is (= ret ::success))))


  (testing "::retry/success?"
    (let [invokes-count (atom 0)
          ret (retry/with-retry (retry/init {::retry/retry? (constantly true)
                                             ::retry/delay (constantly 1)
                                             ::retry/success? (fn [i]
                                                                (< 9 i))})
                (swap! invokes-count inc))]
      (is (= ret 10))))


  (testing "retry count arg"
    (testing "base case"
      (let [invokes-count (atom -1)]
        (try
          (retry/retry* (retry/init {::retry/retry? (fn [n ms ex]
                                                      (< n 10))
                                     ::retry/delay (constantly 1)
                                     ::retry/success? (fn [i]
                                                        (< 5 i))})
                        (fn [c dur]
                          (is (= (swap! invokes-count inc)
                                 c))))
          (catch ExceptionInfo ei
            ::fail)))))


  (testing "exec duration arg"
    (testing "base case"
      (let [last-ms (atom 0)]
        (try
          (retry/retry* (retry/init {::retry/retry? (fn [n ms ex]
                                                      (< n 100))
                                     ::retry/delay (constantly 1)})
                        (fn [c edm]
                          (when-not (zero? c)
                            ;; This is guaranteed to fail due to clock skew
                            ;; but this gives us a good idea that, generally speaking,
                            ;; it's working as designed.
                            (is (< (first (reset-vals! last-ms edm))
                                   edm)
                                "exec-duration-ms only increases"))
                          (throw (ex-info "" {}))))
          (catch ExceptionInfo ei
            ::fail)))))


  (testing "noop"
    (is (= 123
           (retry/with-retry {:something 'else}
             123)))

    (is (= 123
           (retry/with-retry nil
             123))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (retry/init {::retry/retry? (fn [])})))))
