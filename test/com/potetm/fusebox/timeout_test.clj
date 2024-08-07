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
                      (to/with-timeout (to/init {::to/timeout-ms 5})
                        (Thread/sleep 100))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 15))))


  (testing "base case - no sleeping"
    (let [[t ret] (timing
                    (try
                      (to/with-timeout (to/init {::to/timeout-ms 5})
                        ;; benchmarking says this is about 30ms
                        (doseq [_ (range 10000000)]))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 15))))


  (testing "interrupt"
    (let [intr? (volatile! false)
          [t ret] (timing
                    (try
                      (to/with-timeout (to/init {::to/timeout-ms 5})
                        (try
                          (Thread/sleep 100)
                          (catch InterruptedException ie
                            (vreset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 15))
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
      (is (< t 15))
      (is (= false @intr?))))


  (testing "try-interruptible"
    (is (thrown? InterruptedException
                 (to/try-interruptible
                   (throw (InterruptedException.))
                   (catch InterruptedException ie
                     ::CAUGHT!)))))

  (testing "noop"
    (is (= 123
           (to/with-timeout {:something 'else}
             123)))

    (is (= 123
           (to/with-timeout nil
             123))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (to/init {:foo :bar})))))
