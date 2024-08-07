(ns com.potetm.fusebox.registry-test
  (:require
    [clojure.test :refer :all])
  (:require
    [com.potetm.fusebox.registry :as reg]
    [com.potetm.fusebox.retry :as retry]
    [com.potetm.fusebox.timeout :as to]))


(deftest register-test
  (testing "it works"
    (let [spec (merge (retry/init {::retry/retry? (fn [c dur ex]
                                                    (< c 10))
                                   ::retry/delay (constantly 10)})
                      (to/init {::to/timeout-ms 500}))]
      (reg/register! ::my-key
                     spec)
      (is (= spec (reg/get ::my-key)))
      (reg/un-register! ::my-key))))
