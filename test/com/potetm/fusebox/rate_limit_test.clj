(ns com.potetm.fusebox.rate-limit-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.rate-limit :as rl])
  (:import
    (clojure.lang ExceptionInfo)))


(deftest rate-limit-test
  (testing "it works"
    (let [invokes-count (atom 0)
          rl (rl/init {::rl/bucket-size 2
                       ::rl/period-ms 200
                       ::rl/wait-timeout-ms 500})]
      (try
        (into []
              (map (fn [i]
                     (future (rl/with-rate-limit rl
                               (swap! invokes-count inc)))))
              (range 5))
        (is (= 2 @invokes-count))
        (Thread/sleep 250)
        (is (= 4 @invokes-count))
        (Thread/sleep 250)
        (is (= 5 @invokes-count))
        (finally
          (rl/shutdown rl)))))

  (testing "noop"
    (is (= 123
           (rl/with-rate-limit {:something 'else}
             123)))

    (is (= 123
           (rl/with-rate-limit nil
             123))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (rl/init {::rl/bucket-size 1})))))

(comment
  @(def rl (rl/init {::rl/bucket-size 2
                     ::rl/period-ms 100
                     ::rl/wait-timeout-ms 500}))
  rl
  (rl/shutdown *1))
