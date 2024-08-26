(ns com.potetm.fusebox.cljs.bulkhead-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.bulkhead :as bh]
    [com.potetm.promise :as p]))


(deftest bulkhead-test
  (testing "it works"
    (async done
      (let [bh (bh/init {::bh/concurrency 2
                         ::bh/wait-timeout-ms 50})]
        (p/await [ret (p/all-settled
                        (into []
                              (map (fn [i]
                                     (-> (bh/with-bulkhead bh
                                           (p/timeout (constantly :done!)
                                                      200))
                                         (.catch (fn [e]
                                                   :error)))))
                              (range 3)))]
          (is (= (map #(unchecked-get % "value")
                      ret)
                 [:done! :done! :error]))
          (p/await [ret (bh/with-bulkhead bh
                          (p/resolve :done!))]
            (is (= :done! ret))
            (done)))))))


(deftest noop
  (testing "noop"
    (async done
      (p/await [res (bh/with-bulkhead {:something 'else}
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest nil-test
  (testing "nil"
    (async done
      (p/await [res (bh/with-bulkhead nil
                      (p/resolve 123))]
        (is (= 123 res))
        (done)))))


(deftest disable
  (testing "disable"
    (async done
      (let [bh (bh/disable (bh/init {::bh/concurrency 2
                                     ::bh/wait-timeout-ms 100}))]
        (p/await [res (p/all (into []
                                   (map (fn [i]
                                          (bh/with-bulkhead bh
                                            (p/timeout (constantly :done!)
                                                       200))))
                                   (range 3)))]
          (is (= [:done! :done! :done!]
                 (vec res)))
          (done))))))
