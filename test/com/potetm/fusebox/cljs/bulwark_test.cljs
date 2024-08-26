(ns com.potetm.fusebox.cljs.bulwark-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.bulkhead :as bh]
    [com.potetm.fusebox.cljs.bulwark :as bw]
    [com.potetm.fusebox.cljs.circuit-breaker :as cb]
    [com.potetm.fusebox.cljs.fallback :as fallback]
    [com.potetm.fusebox.cljs.rate-limit :as rl]
    [com.potetm.fusebox.cljs.retry :as retry]
    [com.potetm.fusebox.cljs.timeout :as to]
    [com.potetm.promise :as p]))


(deftest bulwark-test
  (testing "it works"
    (async done
      (let [spec (merge (retry/init {::retry/retry? (fn [c dur ex]
                                                      (< c 10))
                                     ::retry/delay (constantly 10)})
                        (to/init {::to/timeout-ms 500})
                        (fallback/init {::fallback/fallback (fn [ex]
                                                              :yes!)})
                        (cb/init {::cb/next-state (partial cb/next-state:default
                                                           {:fail-pct 0.5
                                                            :slow-pct 0.5
                                                            :wait-for-count 3
                                                            :open->half-open-after-ms 100})
                                  ::cb/hist-size 10
                                  ::cb/half-open-tries 3
                                  ::cb/slow-call-ms 100})
                        (rl/init {::rl/bucket-size 10
                                  ::rl/period-ms 1000
                                  ::rl/wait-timeout-ms 100})
                        (bh/init {::bh/concurrency 5
                                  ::bh/wait-timeout-ms 100}))]
        (p/await [res (bw/bulwark spec
                        (p/timeout (constantly [:something :dangerous])
                                   100))]
          (is (= [:something :dangerous]
                 res))
          (bw/shutdown spec)
          (done))))))


(deftest disable
  (testing "disable"
    (async done
      (let [spec (merge (retry/init {::retry/retry? (fn [c dur ex]
                                                      (< c 10))
                                     ::retry/delay (constantly 10)})
                        (to/init {::to/timeout-ms 500})
                        (fallback/init {::fallback/fallback (fn [ex]
                                                              :yes!)})
                        (cb/init {::cb/next-state (partial cb/next-state:default
                                                           {:fail-pct 0.5
                                                            :slow-pct 0.5
                                                            :wait-for-count 3
                                                            :open->half-open-after-ms 100})
                                  ::cb/hist-size 10
                                  ::cb/half-open-tries 3
                                  ::cb/slow-call-ms 100})
                        (rl/init {::rl/bucket-size 10
                                  ::rl/period-ms 1000
                                  ::rl/wait-timeout-ms 100})
                        (bh/init {::bh/concurrency 5
                                  ::bh/wait-timeout-ms 100}))]
        (p/await [res (bw/bulwark (bw/disable spec)
                        (p/timeout (constantly [:something :dangerous])
                                   100))]
          (is (= [:something :dangerous]
                 res))
          (bw/shutdown spec)
          (done))))))


(deftest retry&rate-limit
  (testing "retry and rate limit together"
    (async done
      (let [spec (merge (retry/init {::retry/retry? (fn [c dur ex]
                                                      (< c 6))
                                     ::retry/delay (constantly 10)})
                        (rl/init {::rl/bucket-size 2
                                  ::rl/period-ms 200
                                  ::rl/wait-timeout-ms 500}))
            invokes-count (atom 0)]
        (p/await [[t ret] (p/timing (retry/with-retry spec
                                      (rl/with-rate-limit spec
                                        (p/promise (fn [yes no]
                                                     (if (< (swap! invokes-count inc) 5)
                                                       (no :error)
                                                       (yes :done!)))))))]
          (is (= 5 @invokes-count))
          (is (< 400 t))
          (done))))))
