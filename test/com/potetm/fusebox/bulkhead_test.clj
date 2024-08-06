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
          res (sort-by (fn [v]
                         (if (instance? Exception v)
                           ::z
                           v))
                       (into []
                             (map (fn [f]
                                    (try @f
                                         (catch ExecutionException ee
                                           (.getCause ee)))))
                             ;; Need to immediately submit all futures before
                             ;; resolving any of them.
                             (into []
                                   (map (fn [i]
                                          (future (bh/with-bulkhead bh
                                                    (Thread/sleep 200)
                                                    ::done!))))
                                   (range 3))))]
      (is (= [::done! ::done!] (take 2 res)))
      (let [ex (last res)]
        (is (= ExceptionInfo (type ex)))
        (is (= "fusebox timeout" (ex-message ex))))

      (is (= ::done!
             (bh/with-bulkhead bh
               ::done!)))))

  (testing "noop"
    (is (= 123
           (bh/with-bulkhead {:something 'else}
             123)))

    (is (= 123
           (bh/with-bulkhead nil
             123)))))


(comment
  @(def futs
     (let [bh (bh/bulkhead {::bh/concurrency 2
                            ::bh/timeout-ms 100})]
       (sort-by
         (into []
               (map (fn [f]
                      (try @f
                           (catch ExecutionException ee
                             ee))))
               (into []
                     (map (fn [i]
                            (future (bh/with-bulkhead bh
                                      (Thread/sleep 400)
                                      ::done!))))
                     (range 3))))))

  (second futs)
  @(peek futs)

  )
