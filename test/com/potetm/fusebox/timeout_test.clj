(ns com.potetm.fusebox.timeout-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.timeout :as to])
  (:import
    (clojure.lang ExceptionInfo)))


(defmacro timing [& body]
  `(let [start# (System/currentTimeMillis)
         ret# (do ~@body)
         end# (System/currentTimeMillis)]
     [(- end# start#) ret#]))


(deftest timeout-test
  (testing "base case"
    (let [[t ret] (timing
                    (try
                      (to/with-timeout {::to/timeout-ms 5}
                                       (Thread/sleep 50))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))))


  (testing "base case - no sleeping"
    (let [[t ret] (timing
                    (try
                      (to/with-timeout {::to/timeout-ms 5}
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
                      (to/with-timeout {::to/timeout-ms 5}
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
                      (to/with-timeout {::to/timeout-ms 5
                                        ::to/interrupt? false}
                                       (try
                                         (Thread/sleep 50)
                                         (catch InterruptedException ie
                                           (reset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 10))
      (is (= false @intr?)))))
