(ns com.potetm.fusebox.fallback-test
  (:require
    [clojure.test :refer :all]
    [com.potetm.fusebox.fallback :as fallback])
  (:import
    (clojure.lang ExceptionInfo)))


(deftest fallback-test
  (testing "it works"
    (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                         123)})]
      (is (= 123
             (fallback/with-fallback fallback
               (throw (ex-info "" {})))))))


  (testing "you can rethrow the ex"
    (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                         (throw ex))})]
      (is (thrown? ExceptionInfo
                   (fallback/with-fallback fallback
                     (throw (ex-info "" {})))))))


  (testing "it does nothing if an exception isn't thrown"
    (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                         (throw ex))})]
      (is (= 456
             (fallback/with-fallback fallback
               456)))))

  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (fallback/init {:some 1}))))

  (testing "disable"
    (let [fallback (fallback/disable (fallback/init {::fallback/fallback (fn [ex]
                                                                           123)}))]
      (is (thrown? ExceptionInfo
                   (fallback/with-fallback fallback
                     (throw (ex-info "" {}))))))))
