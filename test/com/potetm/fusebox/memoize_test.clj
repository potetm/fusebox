(ns com.potetm.fusebox.memoize-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.memoize :as memo]))


(deftest memo-test
  (testing "it works"
    (let [invokes-count (atom 0)
          m (memo/memoize {::memo/fn (fn [i]
                                       (swap! invokes-count inc)
                                       (+ 3 i))})]
      (is (= 4 (memo/get m 1)))
      (is (= 1 @invokes-count))
      (is (= 4 (memo/get m 1)))
      (is (= 1 @invokes-count))
      (is (= 5 (memo/get m 2)))
      (is (= 2 @invokes-count)))))
