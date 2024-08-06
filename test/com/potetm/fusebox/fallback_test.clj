(ns com.potetm.fusebox.fallback-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.fallback :as fallback])
  (:import (clojure.lang ExceptionInfo)))


(deftest fallback-test
  (testing "it works"
    (is (= 123
           (fallback/with-fallback {::fallback/fallback (fn [ex]
                                                          123)}
             (throw (ex-info "" {}))))))


  (testing "you can rethrow the ex"
    (is (thrown? ExceptionInfo
                 (fallback/with-fallback {::fallback/fallback (fn [ex]
                                                                (throw ex))}
                   (throw (ex-info "" {}))))))


  (testing "it does nothing if an exception isn't thrown"
    (is (= 456
           (fallback/with-fallback {::fallback/fallback (fn [ex]
                                                          (throw ex))}
             456)))))
