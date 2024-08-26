(ns com.potetm.fusebox.cljs.registry-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.registry :as reg]
    [com.potetm.fusebox.cljs.retry :as retry]
    [com.potetm.fusebox.cljs.timeout :as to]))

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

