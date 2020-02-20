(ns com.potetm.fusebox
  (:require
    [clojure.tools.logging :as log])
  (:import
    (java.util.concurrent Executors
                          ScheduledThreadPoolExecutor
                          ThreadFactory
                          ThreadLocalRandom
                          TimeoutException
                          TimeUnit)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unit Conversion                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn to-ns [[v unit]]
  (let [u (case unit
            (:nano :nanos :nanosecond :nanoseconds)
            TimeUnit/NANOSECONDS

            (:micro :micros :microsecond :microseconds)
            TimeUnit/MICROSECONDS

            (:milli :millis :millisecond :milliseconds)
            TimeUnit/MILLISECONDS

            (:sec :secs :second :seconds)
            TimeUnit/SECONDS

            (:min :mins :minute :minutes)
            TimeUnit/MINUTES

            (:hour :hours)
            TimeUnit/HOURS

            (:day :days)
            TimeUnit/DAYS)]
    (.toNanos u v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities                                                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro try-interruptible
  "Guarantees that an InterruptedException will be immediately rethrown.

  This is preferred to clojure.core/try inside a with-timeout call."
  [& body]
  (let [[pre-catch catches+finally] (split-with (fn [n]
                                                  (not (and (list? n)
                                                            (or (= (first n)
                                                                   'catch)
                                                                (= (first n)
                                                                   'finally)))))
                                                body)]
    `(try
       (do ~@pre-catch)
       (catch InterruptedException ie#
         (throw ie#))
       ~@catches+finally)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timeout                                                                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^{:tag ScheduledThreadPoolExecutor
           :private true}
         timeout-scheduler
         (delay (let [tc (atom -1)
                      ^ScheduledThreadPoolExecutor exec
                      (Executors/newScheduledThreadPool
                        4
                        (reify ThreadFactory
                          (newThread [this r]
                            (doto (Thread. r)
                              (.setName (str "fusebox-timeout-scheduler-thread"
                                             (swap! tc inc)))
                              (.setDaemon true)))))]
                  exec)))


(defn timeout* [{to :fusebox/timeout} f]
  (let [ns (to-ns to)
        t (Thread/currentThread)
        done (atom nil)]
    (try
      (.schedule @timeout-scheduler
                 ^Callable (fn []
                             (when (compare-and-set! done
                                                     nil
                                                     ::timeout)
                               (.interrupt t)))
                 ^long ns
                 TimeUnit/NANOSECONDS)
      (let [ret (f)]
        (compare-and-set! done nil ::done)
        ret)
      (catch InterruptedException ie
        (if (= @done ::timeout)
          (throw (TimeoutException. (str "fusebox timeout after " to)))
          (throw ie))))))


(defmacro with-timeout [spec & body]
  `(timeout* ~spec
             (fn []
               ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Retry                                                                      ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *retry-count*)
(def ^:dynamic *exec-duration-ms*)

(defn retry* [{retry? :fusebox/retry?
               delay :fusebox/retry-delay}
              f]
  (let [start (System/currentTimeMillis)
        exec-dur #(- (System/currentTimeMillis)
                     start)]
    (loop [n 0]
      (when-not (zero? n)
        (Thread/sleep (delay n
                             (exec-dur))))
      (let [[ret v] (try [::success (binding [*retry-count* n
                                              *exec-duration-ms* (exec-dur)]
                                      (f))]
                         (catch Exception e
                           [::error e]))]
        (case ret
          ::success v
          ::error (if (retry? (inc n)
                              (exec-dur)
                              v)
                    (recur (inc n))
                    (throw v)))))))


(defn retry-times [max retry-count]
  (<= retry-count max))


(defn retry-for [max-millis exec-duration]
  (<= exec-duration max-millis))


(defn delay-exp [retry-count]
  (long (* 100
           (Math/pow 2 retry-count))))


(defn delay-linear [factor retry-count]
  (long (* retry-count
           factor)))


(defn jitter [jitter-factor delay]
  (let [jit (long (* jitter-factor
                     delay))]
    (+ delay
       (.nextLong (ThreadLocalRandom/current)
                  (- jit)
                  jit))))


(defmacro with-retry [spec & body]
  `(retry* ~spec
           (fn [] ~@body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circuit Breaker                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^{:tag ScheduledThreadPoolExecutor
           :private true}
         circuit-breaker-scheduler
         (delay (let [tc (atom -1)
                      ^ScheduledThreadPoolExecutor exec
                      (Executors/newScheduledThreadPool
                        1
                        (reify ThreadFactory
                          (newThread [this r]
                            (doto (Thread. r)
                              (.setName (str "fusebox-circuit-breaker-scheduler-thread"
                                             (swap! tc inc)))
                              (.setDaemon true)))))]
                  exec)))


(defn sliding-vec-conj [vec size val]
  (conj (if (< (count vec) size)
          vec
          (subvec vec 1 size))
        val))


(defn record [{lim :fusebox/record-limit :as s}
              event-type]
  (update s
          :fusebox/record
          sliding-vec-conj
          lim
          event-type))


(defn process-transition [s {t :to
                             pred :if}]
  (cond
    pred (when (pred s)
           (assoc s
             :fusebox/allow-pct t
             :fusebox/record []))
    :else (assoc s
            :fusebox/allow-pct t
            :fusebox/record [])))


(defn transition [{prev :fusebox/allow-pct
                   trns :fusebox/states :as s}]
  (or (some (partial process-transition s)
            (get-in trns
                    [prev :transitions]))
      s))


(defn- schedule!
  ([cb [prev curr]]
   (schedule! cb prev curr))
  ([cb
    {prev-allow :fusebox/allow-pct}
    {curr-allow :fusebox/allow-pct :as s}]
   (when (not= prev-allow
               curr-allow)
     (when-some [{a :after :as txn} (get-in s
                                            [:fusebox/states
                                             curr-allow
                                             :schedule])]
       (let [^Callable sched
             (fn sched []
               (try
                 (let [[{prev-pct :fusebox/allow-pct :as prev}
                        new]
                       (swap-vals! cb
                                   (fn [{allow-pct :fusebox/allow-pct :as s}]
                                     (if (= curr-allow allow-pct)
                                       (or (process-transition s txn)
                                           s)
                                       ;; intervening transition, our transition
                                       ;; is not applicable.
                                       s)))]
                   (if (and
                         ;; No intervening transition.
                         (= curr-allow prev-pct)
                         ;; No transition occurred
                         (= prev new))
                     ;; validation error, transition to self
                     (.schedule @circuit-breaker-scheduler
                                ^Callable sched
                                ^long (to-ns a)
                                TimeUnit/NANOSECONDS)))
                 (catch Exception e
                   (log/error "error in scheduler"
                              e))))]

         (.schedule @circuit-breaker-scheduler
                    sched
                    ^long (to-ns a)
                    TimeUnit/NANOSECONDS)
         nil)))))


(defn record! [cb event]
  (swap-vals! cb
              (comp transition record)
              event)
  nil)


(defn allow? [{pct :fusebox/allow-pct}]
  (or (= pct 100)
      (<= (.nextInt (ThreadLocalRandom/current)
                    101)
          pct)))


(defn- ratio->pred [{v :value
                     vs :value-set
                     [n d :as rat] :ratio}]
  (when rat
    (let [chk (if v
                (partial = v)
                (partial contains? vs))]
      (fn [{r :fusebox/record}]
        (<= n
            (count (into []
                         (comp (take d)
                               (filter chk))
                         (rseq r))))))))


(defn- normalize [{sts :fusebox/states :as state}]
  (let [fail-ratio-pred (fn [trn]
                          (if-let [p (ratio->pred trn)]
                            (assoc trn :if p)
                            trn))]
    (assoc state
      :fusebox/states (into {}
                            (map (fn [[ap {sch :schedule
                                           trns :transitions :as st}]]
                                   [ap (assoc st
                                         :schedule (fail-ratio-pred sch)
                                         :transitions (mapv fail-ratio-pred
                                                            trns))]))
                            sts))))


(def defaults
  {:fusebox/allow-pct 100
   :fusebox/record []
   :fusebox/record-limit 128
   :fusebox/states {100 {:transitions [{:to 0
                                        :ratio [3 5]
                                        :value :failure}]}

                    50 {:schedule {:to 100
                                   :after [1 :minute]
                                   :ratio [10 10]
                                   :value :success}
                        :transitions [{:to 0
                                       :ratio [3 5]
                                       :value :failure}]}

                    0 {:schedule {:to 50
                                  :after [1 :minute]}}}})


(defn circuit-breaker
  ([]
   (circuit-breaker {}))
  ([init]
   (let [cb (atom (normalize (merge defaults
                                    init)))]
     (add-watch cb
                ::schedule-transition
                (fn [_k _cb old new]
                  (schedule! cb old new)))
     cb)))


(defn shutdown [cb]
  (remove-watch cb ::schedule-transition))


(defn with-circuit-breaker* [{cb :fusebox/circuit-breaker} f]
  (let [curr @cb]
    (if (allow? curr)
      (try
        (let [ret (f)]
          (record! cb :success)
          ret)
        (catch Exception e
          (record! cb :failure)
          (throw e)))
      (throw (ex-info "fusebox circuit breaker open"
                      (dissoc curr :fusebox/states))))))


(defmacro with-circuit-breaker [spec & body]
  `(with-circuit-breaker* ~spec
                          (fn []
                            ~@body)))


(defmacro failsafe [spec & body]
  `(with-retry spec
               (with-circuit-breaker spec
                                     (with-timeout spec
                                                   ~@body))))

(comment
  (def spec {:fusebox/circuit-breaker (circuit-breaker)
             :fusebox/retry-delay (fn [retry-count exec-duration]
                                    (jitter 0.80
                                            (delay-exp retry-count)))
             :fusebox/retry? (fn [retry-count exec-duration last-error]
                               (retry-times 5 retry-count))
             :fusebox/timeout [1 :seconds]})

  (failsafe spec
            (Thread/sleep 2000))

  (shutdown (:fusebox/circuit-breaker spec))
  )
