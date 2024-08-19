(ns com.potetm.fusebox.bulwark-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.bulkhead :as bh]
    [com.potetm.fusebox.bulwark :as bw]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [com.potetm.fusebox.fallback :as fallback]
    [com.potetm.fusebox.rate-limit :as rl]
    [com.potetm.fusebox.retry :as retry]
    [com.potetm.fusebox.timeout :as to]))

(deftest bulwark-test
  (testing "it works"
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
      (is (= [:something :dangerous]
             (bw/bulwark spec
               (Thread/sleep 100)
               [:something :dangerous])))

      (bw/shutdown spec)))

  (testing "disable"
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
                                ::bh/wait-timeout-ms 100}))
          d (bw/disable spec)]
      (is (= [:something :dangerous]
             (bw/bulwark d
               (Thread/sleep 100)
               [:something :dangerous])))
      (bw/shutdown spec))))
