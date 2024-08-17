(ns dev.chart
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.hypirion.clj-xchart :as xchart])
  (:import (java.math RoundingMode)))


(defn histogram [{{h :histogram} :statistics}]
  (xchart/category-chart* {"data" {:x (into []
                                            (map (fn [[[_s e] _meas]]
                                                   e))
                                            h)
                                   :y (into []
                                            (map peek)
                                            h)}}))


(defn percentiles [{{p :percentiles} :statistics}]
  (let [p (sort-by key p)]
    (xchart/xy-chart {"data" {:x (into []
                                       (map key)
                                       p)
                              :y (into []
                                       (map val)
                                       p)}})))


(defn score [title-append all group]
  (xchart/category-chart* (cond-> {"Baseline" {:x ["Serial" "Parallel"]
                                               :y [(get-in all [:baseline :serial :score 0])
                                                   (get-in all [:baseline :parallel :score 0])]}
                                   "Fusebox" {:x ["Serial" "Parallel"]
                                              :y [(get-in all [group :fusebox :score 0])
                                                  (get-in all [group :fusebox-parallel :score 0])]
                                              :error-bars [(get-in all [group :fusebox :score-error])
                                                           (get-in all [group :fusebox-parallel :score-error])]}}
                            (get-in all [group :r4j])
                            (assoc
                              "Resilience4J" {:x ["Serial" "Parallel"]
                                              :y [(get-in all [group :r4j :score 0])
                                                  (get-in all [group :r4j-parallel :score 0])]
                                              :error-bars [(get-in all [group :r4j :score-error])
                                                           (get-in all [group :r4j-parallel :score-error])]}))
                          {:title (str (name group) title-append)
                           :y-axis {:title "Average execution time (us/op)"}}))


(defn data [f]
  (let [meta {:baseline/no-contention {:display "Baseline"
                                       :group :baseline
                                       :type :serial}
              :baseline/with-contention {:display "Baseline (Parallel)"
                                         :group :baseline
                                         :type :parallel}

              :run:bulkhead/no-contention {:display "Fusebox"
                                           :group :bulkhead
                                           :type :fusebox}
              :run:bulkhead:r4j/no-contention {:display "Resilience4j"
                                               :group :bulkhead
                                               :type :r4j}
              :run:bulkhead/with-contention {:display "Fusebox (Parallel)"
                                             :group :bulkhead
                                             :type :fusebox-parallel}
              :run:bulkhead:r4j/with-contention {:display "Resilience4j (Parallel)"
                                                 :group :bulkhead
                                                 :type :r4j-parallel}


              :run:circuit-breaker/no-contention {:display "Fusebox"
                                                  :group :circuit-breaker
                                                  :type :fusebox}
              :run:circuit-breaker:r4j/no-contention {:display "Resilience4j"
                                                      :group :circuit-breaker
                                                      :type :r4j}
              :run:circuit-breaker/with-contention {:display "Fusebox (Parallel)"
                                                    :group :circuit-breaker
                                                    :type :fusebox-parallel}
              :run:circuit-breaker:r4j/with-contention {:display "Resilience4j (Parallel)"
                                                        :group :circuit-breaker
                                                        :type :r4j-parallel}


              :run:fallback/no-contention {:display "Fusebox"
                                           :group :fallback
                                           :type :fusebox}
              :run:fallback/with-contention {:display "Fusebox"
                                             :group :fallback
                                             :type :fusebox-parallel}


              :run:memoize/no-contention {:display "Fusebox"
                                          :group :memoize
                                          :type :fusebox}
              :run:memoize/with-contention {:display "Fusebox (Parallel)"
                                            :group :memoize
                                            :type :fusebox-parallel}

              :run:retry/no-contention {:display "Fusebox"
                                        :group :retry
                                        :type :fusebox}
              :run:retry:r4j/no-contention {:display "Resilience4j"
                                            :group :retry
                                            :type :r4j}
              :run:retry/with-contention {:display "Fusebox (Parallel)"
                                          :group :retry
                                          :type :fusebox-parallel}
              :run:retry:r4j/with-contention {:display "Resilience4j (Parallel)"
                                              :group :retry
                                              :type :r4j-parallel}

              :run:rate-limiter/no-contention {:display "Fusebox"
                                               :group :rate-limit
                                               :type :fusebox}
              :run:rate-limiter:r4j/no-contention {:display "Resilience4j"
                                                   :group :rate-limit
                                                   :type :r4j}
              :run:rate-limiter/with-contention {:display "Fusebox (Parallel)"
                                                 :group :rate-limit
                                                 :type :fusebox-parallel}
              :run:rate-limiter:r4j/with-contention {:display "Resilience4j (Parallel)"
                                                     :group :rate-limit
                                                     :type :r4j-parallel}

              :run:timeout/no-contention {:display "Fusebox"
                                          :group :timeout
                                          :type :fusebox}
              :run:timeout:r4j/no-contention {:display "Resilience4j"
                                              :group :timeout
                                              :type :r4j}
              :run:timeout/with-contention {:display "Fusebox (Parallel)"
                                            :group :timeout
                                            :type :fusebox-parallel}
              :run:timeout:r4j/with-contention {:display "Resilience4j (Parallel)"
                                                :group :timeout
                                                :type :r4j-parallel}}]
    (update-vals (group-by :group
                           (into []
                                 (map (fn [{n :name :as res}]
                                        (merge res (meta n))))
                                 (edn/read-string (slurp f))))
                 (fn [grp]
                   (into {}
                         (map (juxt :type identity))
                         grp)))))

(defn round [d]
  (.setScale (bigdec d)
             6
             RoundingMode/HALF_UP))


(defn table [all group]
  (str "## " (name group) "\n"
       (str/join "\n"
                 (map (fn [l]
                        (str "|" (str/join "|" l) "|"))
                      (concat
                        [["Test" "Baseline" "Avg. Execution Time" "Approx. Overhead" "Error"]
                         ["---" "---" "---" "---" "---"]
                         (let [bl (round (get-in all [:baseline :serial :score 0]))
                               s (round (get-in all [group :fusebox :score 0]))]
                           ["Fusebox" bl s (- s bl) (round (get-in all [group :fusebox :score-error]))])]
                        (when (get-in all [group :r4j])
                          (let [bl (round (get-in all [:baseline :serial :score 0]))
                                s (round (get-in all [group :r4j :score 0]))]
                            [["Resilience4J" bl s (- s bl) (round (get-in all [group :r4j :score-error]))]]))
                        (let [bl (round (get-in all [:baseline :parallel :score 0]))
                              s (round (get-in all [group :fusebox-parallel :score 0]))]
                          [["Fusebox (Parallel)" bl s (- s bl) (round (get-in all [group :fusebox-parallel :score-error]))]])
                        (when (get-in all [group :r4j-parallel])
                          (let [bl (round (get-in all [:baseline :parallel :score 0]))
                                s (round (get-in all [group :r4j-parallel :score 0]))]
                            [["Resilience4J (Parallel)" bl s (- s bl) (round (get-in all [group :r4j-parallel :score-error]))]])))))))

(comment



  @(def reg
     (merge-with into
                 (data "./benchmarks/2024-08-16-regular.edn")
                 (data "./benchmarks/2024-08-17-rate-limit-regular.edn")
                 (data "./benchmarks/2024-08-17-retry-regular.edn")))

  @(def direct
     (merge-with into
                 (data "./benchmarks/2024-08-16-direct.edn")
                 (data "./benchmarks/2024-08-17-rate-limit-direct.edn")
                 (data "./benchmarks/2024-08-17-retry-direct.edn")))

  data
  (map #(get-in % [:statistics :histogram])
       data)

  (keys data)
  (get-in d [:circuit-breaker :fusebox :score])


  (xchart/view (score " with direct linking" reg :retry))

  (keys d)

  (:retry reg)
  (user/pbcp
    (str/join "\n\n"
              (map #(table direct %)
                   [:retry])))

  (doseq [grp [:retry]]
    (let [xc (score "" reg grp)]
      (io/copy (xchart/to-bytes xc :jpg)
               (io/file "./docs" (str (name grp) ".jpg")))))
  )
