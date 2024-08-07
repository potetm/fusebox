(ns com.potetm.fusebox.memoize-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.memoize :as memo])
  (:import
    (clojure.lang ExceptionInfo)))


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
      (is (= 2 @invokes-count))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (memo/init {:some 1})))))
