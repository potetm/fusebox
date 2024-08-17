(ns dev.jmh
  (:require
    [build :as build]
    [clojure.java.io :as io]
    [com.potetm.fusebox.bulkhead :as bh]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [com.potetm.fusebox.fallback :as fallback]
    [com.potetm.fusebox.memoize :as memo]
    [com.potetm.fusebox.rate-limit :as rl]
    [com.potetm.fusebox.retry :as retry]
    [com.potetm.fusebox.timeout :as to]
    [dev.resilience4j :as r4j]
    [jmh.core :as jmh])
  (:import
    (io.github.resilience4j.timelimiter TimeLimiter)
    (org.openjdk.jmh.infra Blackhole)))


(set! *warn-on-reflection* true)


(defn circuit-breaker []
  (cb/init {::cb/next-state #(cb/next-state:default {:fail-pct 0.5
                                                     :slow-pct 1
                                                     :wait-for-count 10
                                                     :open->half-open-after-ms 60000}
                                                    %)
            ::cb/hist-size 100
            ::cb/half-open-tries 10
            ::cb/slow-call-ms 60000}))


(defn bulkhead []
  (bh/init {::bh/concurrency 25
            ::bh/wait-timeout-ms 0}))


(defn memo []
  (memo/init {::memo/fn (fn [_]
                          (Blackhole/consumeCPU 100)
                          "done!")}))


(defn fallback []
  (fallback/init {::fallback/fallback (fn [_]
                                        "done!")}))


(defn rate-limiter []
  (rl/init {::rl/bucket-size 1000000
            ::rl/period-ms 1
            ::rl/wait-timeout-ms 5000}))


(defn retry []
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 3))
               ::retry/delay (constantly 500)}))


(defn timeout []
  (to/init {::to/timeout-ms 1000}))


(defn run:baseline []
  (Blackhole/consumeCPU 100))


(defn run:circuit-breaker [cb]
  (cb/with-circuit-breaker cb
    (Blackhole/consumeCPU 100)))


(defn run:circuit-breaker:r4j [cb]
  (r4j/with-circuit-breaker cb
    (Blackhole/consumeCPU 100)))


(defn run:bulkhead [bh]
  (bh/with-bulkhead bh
    (Blackhole/consumeCPU 100)))


(defn run:bulkhead:r4j [bh]
  (r4j/with-bulkhead bh
    (Blackhole/consumeCPU 100)))


(defn genstr []
  (str (random-uuid)))


(defn run:memoize [memo k]
  (memo/get memo k))


(defn run:fallback [fb]
  (fallback/with-fallback fb
    (Blackhole/consumeCPU 100)))


(defn run:rate-limiter [rl]
  (rl/with-rate-limit rl
    (Blackhole/consumeCPU 100)))


(defn run:rate-limiter:r4j [rl]
  (r4j/with-rate-limiter rl
    (Blackhole/consumeCPU 100)))


(defn run:retry [retry]
  (retry/with-retry retry
    (Blackhole/consumeCPU 100)))


(defn run:retry:r4j [retry]
  (r4j/with-retry retry
    (Blackhole/consumeCPU 100)))

(defn run:timeout [to]
  (to/with-timeout to
    (Blackhole/consumeCPU 100)))


(defn run:timeout:r4j [to]
  (r4j/with-timeout ^TimeLimiter to
    (Blackhole/consumeCPU 100)))


(def benchmarks
  {:benchmarks (into [{:name :baseline/with-contention
                       :fn `run:baseline
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 8}}
                      {:name :baseline/no-contention
                       :fn `run:baseline
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 1}}
                      {:name :run:memoize/no-contention
                       :fn `run:memoize
                       :args [:state/memo :state/genstr]
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 1}}
                      {:name :run:memoize/with-contention
                       :fn `run:memoize
                       :args [:state/memo :state/genstr]
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 8}}
                      {:name :run:fallback/no-contention
                       :fn `run:fallback
                       :args [:state/fallback]
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 1}}
                      {:name :run:fallback/with-contention
                       :fn `run:fallback
                       :args [:state/fallback]
                       :options {:mode :sample
                                 :output-time-unit :us
                                 :threads 8}}]
                     (mapcat (fn [[sym args]]
                               [{:name (keyword (name sym) "with-contention")
                                 :fn sym
                                 :args args
                                 :options {:mode :sample
                                           :output-time-unit :us
                                           :threads 8}}
                                {:name (keyword (name sym) "no-contention")
                                 :fn sym
                                 :args args
                                 :options {:mode :sample
                                           :output-time-unit :us
                                           :threads 1}}]))

                     [[`run:circuit-breaker [:state/circuit-breaker]]
                      [`run:circuit-breaker:r4j [:state/r4j-circuit-breaker]]

                      [`run:bulkhead [:state/bulkhead]]
                      [`run:bulkhead:r4j [:state/r4j-bulkhead]]

                      [`run:rate-limiter [:state/rate-limiter]]
                      [`run:rate-limiter:r4j [:state/r4j-rate-limiter]]

                      [`run:retry [:state/retry]]
                      [`run:retry:r4j [:state/r4j-retry]]

                      [`run:timeout [:state/timeout]]
                      [`run:timeout:r4j [:state/r4j-timeout]]])
   :states {:genstr `genstr

            :circuit-breaker `circuit-breaker
            :bulkhead `bulkhead
            :memo `memo
            :fallback `fallback
            :rate-limiter `rate-limiter
            :retry `retry
            :timeout `timeout

            :r4j-circuit-breaker `r4j/circuit-breaker
            :r4j-bulkhead `r4j/bulkhead
            :r4j-rate-limiter `r4j/rate-limiter
            :r4j-retry `r4j/retry
            :r4j-timeout `r4j/timeout}

   :options {:direct-linking {:fork {:count 1
                                     :warmups 0
                                     :jvm {:append-args ["-Dclojure.compiler.direct-linking=true"]}}
                              :measurement {:iterations 5
                                            :time [10 :s]}
                              :warmup {:time [10 :s]}}}})


(defn clean []
  (build/clean))


(defn run [args]
  (clean)
  (.mkdirs (io/file build/classes))
  (binding [*compile-path* build/classes]
    (jmh/run benchmarks
             args)))


(comment
  @(def res
     (run {:type :quick
           :status true}))

  @(def res-direct
     (run {:type :direct-linking
           :status true}))

  (def res-retry (run {:type :direct-linking
                       :status true
                       :select [:run:retry/no-contention
                                :run:retry/with-contention]}))
  (def retry rate-limit)
  (def no-record' (run {:type :quick
                        :status true
                        :select [:run:retry/no-contention
                                 :run:retry/with-contention]}))
  no-record

  (spit "./benchmarks/2024-08-16-regular.edn"
        (pr-str res))
  (spit "./benchmarks/2024-08-16-direct.edn"
        (pr-str res-direct))

  (clojure.edn/read-string (slurp "./benchmarks/2024-08-16-regular.edn"))
  (def res *2)

  res
  (def results
    {:reg (into {}
                (map (fn [{n :name s :score se :score-error}]
                       [n]))
                res)})
  (spit "./benchmarks/2024-08-17-retry-regular.edn"
        (pr-str res-retry))

  (spit "./benchmarks/2024-08-17-retry-direct.edn"
        (pr-str res-retry))


  (map (juxt :name :score :score-error)
       rate-limit)

  (require 'clj-java-decompiler.core)
  (clj-java-decompiler.core/decompile (retry/with-retry {::retry/retry? (constantly true)
                                                         ::retry/delay (constantly 1)}
                                        (+ 1 1)))
  )
