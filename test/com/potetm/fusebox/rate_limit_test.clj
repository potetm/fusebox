(ns com.potetm.fusebox.rate-limit-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.rate-limit :as rl]))


(deftest rate-limit-test
  (testing "it works"
    (let [invokes-count (atom 0)
          rl (rl/rate-limiter {::rl/bucket-size 2
                               ::rl/period-ms 100
                               ::rl/timeout-ms 500})]
      (try
        (into []
              (map (fn [i]
                     (future (rl/with-rate-limit rl
                               (swap! invokes-count inc)))))
              (range 5))
        (is (= 2 @invokes-count))
        (Thread/sleep 110)
        (is (= 4 @invokes-count))
        (Thread/sleep 110)
        (is (= 5 @invokes-count))
        (finally
          (rl/shutdown rl))))))

(comment
  (rl/rate-limiter {::rl/bucket-size 2
                    ::rl/period-ms 100
                    ::rl/timeout-ms 500})
  (rl/shutdown *1))
