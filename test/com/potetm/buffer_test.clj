(ns com.potetm.buffer-test
  (:require
    [clojure.test :refer :all]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.test.check.clojure-test :as gtest])
  (:import
    (com.potetm.fusebox PersistentCircularBuffer)))

(def coll (gen/vector gen/small-integer))

(gtest/defspec reduce-test 10000
  (prop/for-all [c coll]
    (= (reduce +
               c)
       (reduce +
               (into (PersistentCircularBuffer. (count c))
                     c)))))

(gtest/defspec reduce-with-initial-test 10000
  (prop/for-all [c coll]
    (= (reduce +
               5
               c)
       (reduce +
               5
               (into (PersistentCircularBuffer. (count c))
                     c)))))


(defn slide [n coll]
  (let [n (if (neg? n)
            (+ (count coll) n)
            n)]
    (take (count coll)
          (drop n
                (cycle coll)))))

(gtest/defspec seqing 1024
  (prop/for-all [c (gen/vector gen/small-integer
                               1
                               1024)]
    (= (seq c)
       (seq (into (PersistentCircularBuffer. (count c))
                  c)))))

(gtest/defspec rseqing 1024
  (prop/for-all [c (gen/vector gen/small-integer
                               1
                               1024)]
    (= (rseq c)
       (rseq (into (PersistentCircularBuffer. (count c))
                   c)))))

(gtest/defspec iterator 1024
  (prop/for-all [c (gen/vector gen/small-integer
                               1
                               1024)]
    (= (iterator-seq (.iterator c))
       (iterator-seq (.iterator (into (PersistentCircularBuffer. (count c))
                                      c))))))
