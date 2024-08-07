(ns dev.benchmark
  (:require
    [com.potetm.fusebox.circuit-breaker :as cb]))


(defmacro n-times [times & body]
  `(let [start# (System/nanoTime)]
     (dotimes [_# ~times]
       ~@body)
     (println "Average ns: "
              (double (/ (- (System/nanoTime)
                            start#)
                         ~times)))))


(defmacro n-times-ms [times & body]
  `(let [start# (System/currentTimeMillis)]
     (dotimes [_# ~times]
       ~@body)
     (println "Average ms: "
              (double (/ (- (System/currentTimeMillis)
                            start#)
                         ~times)))))


(defn sliding-vec-conj [vec size val]
  (conj (if (< (count vec) size)
          vec
          (subvec vec 1 size))
        val))


(comment

  (def cb (cb/init {::cb/next-state (partial cb/next-state:default
                                             {:fail-pct 0.5
                                                         :slow-pct 0.5
                                                         :wait-for-count 3
                                                         :open->half-open-after-ms 100})
                               ::cb/hist-size 10
                               ::cb/half-open-tries 3
                               ::cb/slow-call-ms 100}))

  (def cba (::cb/circuit-breaker cb))
  (n-times 1000000
           (cb/record! cba
                       :success
                       10))
  )
