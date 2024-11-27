(ns com.potetm.fusebox.cljs.retry-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.retry :as retry]
    [com.potetm.promise :as p]))


(deftest retry-test
  (testing "base case"
    (async done
      (let [invokes-count (atom 0)]
        (p/await [ret (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                                      (< n 10))
                                                     ::retry/delay (constantly 1)})
                        (p/promise (fn [yes no]
                                     (swap! invokes-count inc)
                                     (no (ex-info "" {})))))]
          (catch e
                 (is (instance? ExceptionInfo e))
            (is (= 10 @invokes-count))
            (done)))))))


(deftest retry-test:eventual-success
  (testing "base case - eventual success"
    (async done
      (let [invokes-count (atom 0)]
        (p/await [ret (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                                      (< n 10))
                                                     ::retry/delay (constantly 1)})
                        (p/wrap
                          #(if (= 5 (swap! invokes-count inc))
                             :success
                             (throw (ex-info "" {})))))]
          (is (= ret :success))
          (done))))))


(deftest delaying
  (testing "Delaying"
    (async done
      (let [invokes-count (atom 0)]
        (p/await [[ms ret] (p/timing
                             (retry/with-retry (retry/init {::retry/retry? (constantly true)
                                                            ::retry/delay (constantly 100)})
                               (p/wrap (fn []
                                         (when (= 1 (swap! invokes-count inc))
                                           (throw (ex-info "" {})))
                                         ::success))))]
          (is (<= 100 ms))
          (is (= ret ::success))
          (done))))))


(deftest retry-success?-fn
  (testing "::retry/success?"
    (async done
      (let [invokes-count (atom 0)]
        (p/await [ret (retry/with-retry (retry/init {::retry/retry? (constantly true)
                                                     ::retry/delay (constantly 1)
                                                     ::retry/success? (fn [i]
                                                                        (< 9 i))})
                        (p/wrap #(swap! invokes-count inc)))]
          (is (= ret 10))
          (done))))))


(deftest retry-success?-constantly-failing-return-value
  (testing "when ::retry/success? never succeeds last return value is included"
    (async done
           (let [invokes-count (atom 0)]
             (p/await [ret (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                                           (< n 5))
                                                          ::retry/delay (constantly 1)
                                                          ::retry/success? (fn [_]
                                                                             false)})
                             (p/promise (fn [yes no]

                                          (yes {:some :thing :count (swap! invokes-count inc)}))))]
                      (catch e
                        (is (= {:some :thing :count 5}
                               (-> (ex-data e) ::retry/val)))
                        (done)))))))


(deftest retry-count-arg
  (testing "retry count arg"
    (async done
      (let [invokes-count (atom -1)]
        (p/await [_ (retry/with-retry [c dur]
                      (retry/init {::retry/retry? (fn [n ms ex]
                                                    (< n 10))
                                   ::retry/delay (constantly 1)
                                   ::retry/success? (fn [i]
                                                      (< 5 i))})

                      (p/promise (fn [yes no]
                                   (is (= (swap! invokes-count inc)
                                          c))
                                   (no))))]
          (catch e
                 (done)))))))


(deftest exec-duration-arg
  (testing "exec duration arg"
    (async done
      (let [last-ms (atom 0)]
        (p/await [_ (retry/with-retry [c edm]
                      (retry/init {::retry/retry? (fn [n ms ex]
                                                    (< n 100))
                                   ::retry/delay (constantly 1)})
                      (p/promise (fn [yes no]
                                   (when-not (zero? c)
                                     ;; This is guaranteed to fail due to clock skew
                                     ;; but this gives us a good idea that, generally speaking,
                                     ;; it's working as designed.
                                     (is (<= (first (reset-vals! last-ms edm))
                                             edm)
                                         "exec-duration-ms only increases"))
                                   (no))))]
          (catch e
                 (done)))))))


(deftest noop
  (testing "noop"
    (async done
      (p/await [res (retry/with-retry {:something 'else}
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest nil-test
  (testing "nil"
    (async done
      (p/await [res (retry/with-retry nil
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (retry/init {::retry/retry? (fn [])})))))


(deftest disable
  (testing "disable"
    (async done
      (let [invokes-count (atom 0)
            retry (retry/disable (retry/init {::retry/retry? (fn [n ms ex]
                                                               (< n 10))
                                              ::retry/delay (constantly 1)}))]
        (p/await [_ (retry/with-retry retry
                      (p/promise (fn [yes no]
                                   (swap! invokes-count inc)
                                   (no :fail))))]
          (catch e
                 (is (= e :fail))
            (is (= 1 @invokes-count))
            (done)))))))
