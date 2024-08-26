(ns com.potetm.fusebox.cljs.fallback-test
  (:require
    [clojure.test :refer [async deftest is testing]]
    [com.potetm.fusebox.cljs.fallback :as fallback]
    [com.potetm.promise :as p]))


(deftest fallback-test
  (testing "it works"
    (async done
      (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                           123)})]

        (p/await [res (fallback/with-fallback fallback
                        (p/reject))]
          (is (= 123 res))
          (done))))))


(deftest rethrow
  (testing "rethrow"
    (async done
      (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                           (throw ex))})]
        (p/await [_ (fallback/with-fallback fallback
                      (p/reject (ex-info "" {})))]
          (catch e
                 (is (instance? ExceptionInfo e))
            (done)))))))


(deftest success-means-noop
  (testing "it does nothing if an exception isn't thrown"
    (async done
      (let [fallback (fallback/init {::fallback/fallback (fn [ex]
                                                           (throw ex))})]
        (p/await [res (fallback/with-fallback fallback
                        (p/resolve 456))]
          (is (= 456 res))
          (done))))))


(deftest invalid-args
  (testing "invalid args"
    (is (thrown-with-msg? ExceptionInfo
                          #"(?i)invalid"
                          (fallback/init {:some 1})))))


(deftest disable
  (testing "disable"
    (let [fallback (fallback/disable (fallback/init {::fallback/fallback (fn [ex]
                                                                           123)}))]
      (p/await [res (fallback/with-fallback fallback
                      (p/reject (ex-info "" {})))]
        (catch e
               (is (instance? ExceptionInfo e)))))))
