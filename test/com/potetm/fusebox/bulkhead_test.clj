(ns com.potetm.fusebox.bulkhead-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.bulkhead :as bh])
  (:import
    (clojure.lang ExceptionInfo)
    (java.util.concurrent ExecutionException)))


(deftest bulkhead-test
  (testing "it works"
    (let [bh (bh/bulkhead {::bh/concurrency 2
                           ::bh/timeout-ms 100})
          futs (into []
                     (map (fn [i]
                            (future (bh/with-bulkhead bh
                                      (Thread/sleep 200)
                                      ::done!))))
                     (range 3))]
      (is (= ::done! @(first futs)))
      (is (= ::done! @(second futs)))
      (try
        @(peek futs)
        (catch ExecutionException ee
          (let [cause (.getCause ee)]
            (is (= ExceptionInfo (type cause)))
            (is (= "fusebox timeout" (ex-message cause))))))

      (is (= ::done!
             (bh/with-bulkhead bh
               ::done!))))))


(comment
  (def futs
    (let [bh (bh/bulkhead {::bh/concurrency 2
                           ::bh/timeout-ms 100})]
      (into []
            (map (fn [i]
                   (future (bh/with-bulkhead bh
                             (Thread/sleep 200)))))
            (range 3))))

  (second futs)
  @(peek futs)

  )
