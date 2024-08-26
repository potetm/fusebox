(ns com.potetm.fusebox.cljs.timeout-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.timeout :as to]
    [com.potetm.promise :as p]))


(deftest timeout-test
  (testing "base case"
    (async done
      (p/await [[t ret] (p/timing
                          (p/await [_
                                    (to/with-timeout (to/init {::to/timeout-ms 1})
                                      (p/timeout 100))]
                            (catch ei
                                   :timeout)))]
        (is (= ret :timeout))
        (is (< t 15))
        (done)))))


(deftest abort-controller
  (testing "abort controller"
    (async done
      (p/await [[t ret] (p/timing
                          (p/await [_
                                    (to/with-timeout [abort-controller] (to/init {::to/timeout-ms 1})
                                      (js/fetch "https://httpbin.org/delay/1"
                                                (js-obj
                                                  "signal" (.-signal abort-controller))))]
                            :done!
                            (catch ei :timeout)))]
        (is (= ret :timeout))
        (is (< t 15))
        (done)))))


(deftest noop
  (testing "noop"
    (async done
      (p/await [ret (to/with-timeout {:something 'else}
                      (p/resolve 123))]
        (is (= ret 123))
        (done)))))


(deftest nil-test
  (testing "nil"
    (async done
      (p/await [ret (to/with-timeout nil
                      (p/resolve 123))]
        (is (= ret 123))
        (done)))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (to/init {:foo :bar})))))


(deftest disable
  (testing "disable"
    (let [to (to/init {::to/timeout-ms 5})]
      (p/await [[t ret] (p/timing
                          (p/await [res (to/with-timeout (to/disable to)
                                          (p/timeout (constantly :hello!)
                                                     100))]
                            res
                            (catch ex
                                   :timeout)))]
        (is (= ret :hello!))
        (is (< 100 t))))))
