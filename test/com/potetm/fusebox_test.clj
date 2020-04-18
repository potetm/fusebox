(ns com.potetm.fusebox-test
  (:require [clojure.test :refer :all]
            [com.potetm.fusebox :as fb])
  (:import (clojure.lang ExceptionInfo)))


(defmacro timing [& body]
  `(let [start# (System/currentTimeMillis)
         ret# (do ~@body)
         end# (System/currentTimeMillis)]
     [(- end# start#) ret#]))


(deftest timeout-test
  (testing "base case"
    (let [[t ret] (timing
                    (try
                      (fb/with-timeout {::fb/exec-timeout [5 :ms]}
                        (Thread/sleep 50))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))))


  (testing "base case - no sleeping"
    (let [[t ret] (timing
                    (try
                      (fb/with-timeout {::fb/exec-timeout [5 :ms]}
                        ;; benchmarking says this is about 30ms
                        (doseq [_ (range 10000000)]))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))))


  (testing "interrupt"
    (let [intr? (atom false)
          [t ret] (timing
                    (try
                      (fb/with-timeout {::fb/exec-timeout [5 :ms]}
                        (try
                          (Thread/sleep 50)
                          (catch InterruptedException ie
                            (reset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))
      (is (= true @intr?))))


  (testing "no interrupt"
    (let [intr? (atom false)
          [t ret] (timing
                    (try
                      (fb/with-timeout {::fb/exec-timeout [5 :ms]
                                        ::fb/interrupt? false}
                        (try
                          (Thread/sleep 50)
                          (catch InterruptedException ie
                            (reset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))
      (is (= false @intr?)))))


(deftest retry-test
  (testing "base case"
    (let [invokes-count (atom 0)
          ret (try
                (fb/with-retry {::fb/retry? (fn [n ms ex]
                                              (< n 10))
                                ::fb/retry-delay (constantly [1 :ms])}
                  (swap! invokes-count inc)
                  (throw (ex-info "" {})))
                (catch ExceptionInfo ei
                  ::fail))]
      (is (= ret ::fail))
      (is (= 10 @invokes-count))))


  (testing "base case - eventual success"
    (let [invokes-count (atom 0)
          ret (try
                (fb/with-retry {::fb/retry? (fn [n ms ex]
                                              (< n 10))
                                ::fb/retry-delay (constantly [1 :ms])}

                  (if (= 5 (swap! invokes-count inc))
                    ::success
                    (throw (ex-info "" {}))))
                (catch ExceptionInfo ei
                  ::fail))]
      (is (= ret ::success))))


  (testing "fb/*retry-count*"
    (testing "base case"
      (let [invokes-count (atom -1)]
        (try
          (fb/with-retry {::fb/retry? (fn [n ms ex]
                                        (< n 10))
                          ::fb/retry-delay (constantly [1 :ms])}
            (is (= (swap! invokes-count inc)
                   fb/*retry-count*)))
          (catch ExceptionInfo ei
            ::fail)))))

  (testing "fb/*exec-duration-ms*"
    (testing "base case"
      (let [last-ms (atom 0)]
        (try
          (fb/with-retry {::fb/retry? (fn [n ms ex]
                                        (< n 100))
                          ::fb/retry-delay (constantly [1 :ms])}
            (let [edm fb/*exec-duration-ms*]
              (when-not (zero? fb/*retry-count*)
                ;; This is guaranteed to fail due to clock skew
                ;; but this gives us a good idea that, generally speaking,
                ;; it's working as designed.
                (is (< (first (reset-vals! last-ms edm))
                       edm)
                    "exec-duration-ms only increases"))
              (throw (ex-info "" {}))))
          (catch ExceptionInfo ei
            ::fail))))))
