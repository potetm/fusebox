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
      (is (< t 25))))


  (testing "success case"
    (let [[t ret] (timing
                    (try
                      (to/with-timeout (to/init {::to/timeout-ms 500})
                        (Thread/sleep 100)
                        :hello!)
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret :hello!))))


  (testing "base case - no sleeping"
    (let [[t ret] (timing
                    (try
                      (to/with-timeout (to/init {::to/timeout-ms 5})
                        ;; benchmarking says this is about 180ms
                        (doseq [_ (doseq [_ (range 100000000)])]))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 25))))


  (testing "interrupt"
    (let [intr? (volatile! false)
          [t ret] (timing
                    (try
                      (to/with-timeout (to/init {::to/timeout-ms 5})
                        (try
                          (Thread/sleep 200)
                          (catch InterruptedException ie
                            (vreset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 25))
      ;; give it a few millis to receive the interrupt
      (Thread/sleep 5)
      (is (= true @intr?))))


  (testing "no interrupt"
    (let [intr? (atom false)
          [t ret] (timing
                    (try
                      (to/with-timeout {::to/timeout-ms 5
                                        ::to/interrupt? false}
                        (try
                          (Thread/sleep 200)
                          (catch InterruptedException ie
                            (reset! intr? true))))
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret ::timeout))
      (is (< t 25))
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
                          (to/init {:foo :bar}))))

  (testing "disable"
    (let [to (to/init {::to/timeout-ms 5})
          [t ret] (timing
                    (try
                      (to/with-timeout (to/disable to)
                        (Thread/sleep 100)
                        :hello!)
                      (catch ExceptionInfo ei
                        ::timeout)))]
      (is (= ret :hello!))
      (is (<= 100 t)))))


(def ^:dynamic *dynavar*)


(deftest dynamic-var-changes
  (testing "timeout threads can see changes to dynamic vars"
    (binding [*dynavar* :init]
      (let [wait (promise)
            ret (promise)]

        ;; Triggering this is tricky. Kick off a thread, do NOT interrupt,
        ;; let it  timeout so we regain control in the parent thread,
        ;; *then* do our updates.
        (try (to/with-timeout {::to/timeout-ms 1
                               ::to/interrupt? false}
               @wait
               (deliver ret *dynavar*))
             (catch ExceptionInfo _))

        (set! *dynavar* :new)
        (deliver wait nil)
        (is (= @ret :new))))))
