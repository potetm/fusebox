(ns com.potetm.fusebox.cljs.memoize-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.memoize :as memo]))


(deftest memo-test
  (testing "it works"
    (let [invokes-count (atom 0)
          m (memo/init {::memo/fn (fn [i]
                                    (swap! invokes-count inc)
                                    (+ 3 i))})]
      (is (= 4 (memo/get m 1)))
      (is (= 1 @invokes-count))
      (is (= 4 (memo/get m 1)))
      (is (= 1 @invokes-count))
      (is (= 5 (memo/get m 2)))
      (is (= 2 @invokes-count)))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (memo/init {:some 1})))))


(deftest disable
  (testing "disable"
    (let [invokes-count (atom 0)
          m (memo/disable (memo/init {::memo/fn (fn [i]
                                                  (swap! invokes-count inc)
                                                  (+ 3 i))}))]
      (is (= 4 (memo/get m 1)))
      (is (= 1 @invokes-count))
      (is (= 4 (memo/get m 1)))
      (is (= 2 @invokes-count)))))
