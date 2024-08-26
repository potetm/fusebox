(ns com.potetm.fusebox.cljs.rate-limit-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.rate-limit :as rl]
    [com.potetm.promise :as p]))

(deftest rate-limit-test
  (testing "it works"
    (async done
      (let [invokes-count (atom 0)
            rl (rl/init {::rl/bucket-size 2
                         ::rl/period-ms 200
                         ::rl/wait-timeout-ms 500})]
        (dotimes [_ 5]
          (rl/with-rate-limit rl
            (p/promise (fn [yes]
                         (yes (swap! invokes-count inc))))))
        (-> (p/resolve)
            (.then #(is (= 2 @invokes-count))))
        (p/await [_ (p/timeout 250)]
          (is (= 4 @invokes-count))
          (p/await [_ (p/timeout 250)]
            (is (= 5 @invokes-count))
            (rl/shutdown rl)
            (done)))))))


(deftest noop
  (testing "noop"
    (async done
      (p/await [res (rl/with-rate-limit {:something 'else}
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest nil-test
  (testing "nil"
    (async done
      (p/await [res (rl/with-rate-limit nil
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (rl/init {::rl/bucket-size 1})))))


(deftest disable
  (testing "disable"
    (async done
      (let [invokes-count (atom 0)
            rl (rl/init {::rl/bucket-size 2
                         ::rl/period-ms 200
                         ::rl/wait-timeout-ms 500})
            d (rl/disable rl)]
        (into []
              (map (fn [i]
                     (rl/with-rate-limit d
                       (p/wrap #(swap! invokes-count inc)))))
              (range 5))
        (-> (p/resolve)
            (.then (is (= 5 @invokes-count)))
            (.finally (fn []
                        (rl/shutdown rl)
                        (done))))))))
