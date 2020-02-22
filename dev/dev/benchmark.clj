(ns dev.benchmark
  (:require
    [clj-memory-meter.core :as mm]
    [com.potetm.fusebox :as fb])
  (:import
    (com.potetm.fusebox PersistentCircularBuffer)))


(defmacro n-times [times & body]
  `(let [start# (System/nanoTime)]
     (dotimes [_# ~times]
       ~@body)
     (println "Average ns: "
              (double (/ (- (System/nanoTime)
                            start#)
                         ~times)))))


(defn sliding-vec-conj [vec size val]
  (conj (if (< (count vec) size)
          vec
          (subvec vec 1 size))
        val))


(comment

  (def buf (fb/circuit-breaker {:fusebox/record (PersistentCircularBuffer. 32)}))

  (with-redefs [fb/record (fn [spec event-type]
                            (update spec
                                    :fusebox/record
                                    conj
                                    event-type))]
    (n-times 1000000
      (fb/record! buf :success)))
  ; Average ns:  1587.840109

  (:fusebox/record @buf)
  (mm/measure (:fusebox/record @buf)
              :debug true)
  ; => "560 B"

  (def v (fb/circuit-breaker {:fusebox/record-limit 32
                              :fusebox/record []}))

  (with-redefs [fb/record (fn [{lim :fusebox/record-limit :as spec} event-type]
                            (update spec
                                    :fusebox/record
                                    sliding-vec-conj
                                    lim
                                    event-type))]
    (n-times 1000000
      (fb/record! v :success)))
  ; Average ns:  2138.798864

  (:fusebox/record @v)
  (mm/measure (:fusebox/record @v))
  ; => "31.0 MB"

  (mm/measure (into [] (repeat 128 :success)))

  (mm/measure))
