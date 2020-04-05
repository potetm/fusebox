(ns com.potetm.fusebox
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk])
  (:import
    (clojure.lang ExceptionInfo
                  Var)
    (com.potetm.fusebox PersistentCircularBuffer)
    (java.util.concurrent BlockingQueue
                          Executors
                          ExecutorService
                          Future
                          ScheduledThreadPoolExecutor
                          Semaphore
                          SynchronousQueue
                          ThreadFactory
                          ThreadLocalRandom
                          ThreadPoolExecutor
                          TimeoutException
                          TimeUnit)
    (java.util.concurrent.atomic AtomicLong)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unit Conversion                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unit ^TimeUnit [kw]
  (case kw
    (:ns :nano :nanos :nanosecond :nanoseconds)
    TimeUnit/NANOSECONDS

    (:µs :us :micro :micros :microsecond :microseconds)
    TimeUnit/MICROSECONDS

    (:ms :milli :millis :millisecond :milliseconds)
    TimeUnit/MILLISECONDS

    (:s :sec :secs :second :seconds)
    TimeUnit/SECONDS

    (:m :min :mins :minute :minutes)
    TimeUnit/MINUTES

    (:h :hr :hour :hours)
    TimeUnit/HOURS

    (:d :day :days)
    TimeUnit/DAYS))

(defn convert ^long [[v orig] target]
  (.convert (unit target)
            v
            (unit orig)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities                                                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn convey-bindings [f]
  (let [binds (Var/getThreadBindingFrame)]
    (fn []
      (Var/resetThreadBindingFrame binds)
      (f))))


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


(defn circular-buffer [size]
  (PersistentCircularBuffer. size))


(defn num-items
  "Get the number of non-nil items in constant time.

  Note this is different than the size of the buffer, which is fixed."
  [^PersistentCircularBuffer buff]
  (.numItems buff))


(defn pretty-spec
  ([{cb :fusebox/circuit-breaker :as spec}]
   (pretty-spec @cb spec))
  ([cb spec]
   (-> spec
       (dissoc :fusebox/circuit-breaker
               :fusebox/retry-delay
               :fusebox/retry?)
       (update :fusebox/bulkhead dissoc :exec)
       (assoc :fusebox/circuit-breaker-state
              (walk/postwalk (fn [v]
                               (if (map? v)
                                 (cond-> v
                                         (:if v) (dissoc :if)
                                         (:fusebox/record v) (update :fusebox/record
                                                                     #(remove nil? %)))
                                 v))
                             cb)))))


(def edn-props
  (when-some [url (io/resource "fusebox.edn")]
    (edn/read-string (slurp url))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timeout                                                                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^{:tag ExecutorService
           :doc "An unbound threadpool used for reliably timing out calls."
           :private true}
         timeout-threadpool
         (delay (let [tc (AtomicLong. -1)
                      ^ExecutorService exec
                      (Executors/newCachedThreadPool
                        (reify ThreadFactory
                          (newThread [this r]
                            (doto (Thread. r)
                              (.setName (str "fusebox-timeout-scheduler-thread-"
                                             (.incrementAndGet tc)))
                              (.setDaemon true)))))]
                  exec)))

(defn timeout* [{to :fusebox/exec-timeout
                 intr? :fusebox.timeout/interrupt?
                 :or {intr? true}
                 :as spec}
                f]
  (let [fut (.submit ^ExecutorService timeout-threadpool
                     ^Callable (convey-bindings f))]
    (try
      (.get fut
            (convert to :ns)
            (unit :ns))
      (catch InterruptedException ie
        (.cancel fut intr?)
        (throw ie))
      (catch TimeoutException to
        (.cancel fut intr?)
        (throw (ex-info "fusebox timeout"
                        {:fusebox/error :fusebox.error/exec-timeout
                         :fusebox/spec (pretty-spec spec)}))))))


(defmacro with-timeout
  "Evaluates body, aborting if it lasts longer than specified.

  spec is map containing:
    :fusebox/timeout - a time unit tuple (e.g. [10 :seconds])"
  [spec & body]
  `(timeout* ~spec
             (^{:once true} fn* [] ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Retry                                                                      ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:dynamic true
       :doc
       "The number of times a call has been previously attempted.

       This is intended primarily for diagnostic purposes (e.g. occasional
       logging or metrics) in lieu of callback hooks."}
  *retry-count*)

(def ^{:dynamic true
       :doc
       "The approximate time spent attempting a call.

       This is intended primarily for diagnostic purposes (e.g. occasional
       logging or metrics) in lieu of callback hooks."}
  *exec-duration-ms*)

(defn retry* [{retry? :fusebox/retry?
               delay :fusebox/retry-delay :as spec}
              f]
  (let [start (System/currentTimeMillis)
        exec-dur #(- (System/currentTimeMillis)
                     start)]
    (loop [n 0]
      (when-not (zero? n)
        (Thread/sleep (delay n
                             (exec-dur))))
      (let [[ret v] (try-interruptible
                      [::success (binding [*retry-count* n
                                           *exec-duration-ms* (exec-dur)]
                                   (f))]
                      (catch Exception e
                        [::error e]))]
        (case ret
          ::success v
          ::error (let [ed (exec-dur)]
                    (if (retry? (inc n)
                                ed
                                v)
                      (recur (inc n))
                      (throw (ex-info "fusebox retries exhausted"
                                      {:fusebox/error :fusebox.error/retries-exhausted
                                       :fusebox/num-retries n
                                       :fusebox/exec-duration-ms ed
                                       :fusebox/spec (pretty-spec spec)}
                                      v)))))))))


(defn delay-exp
  "Calculate an exponential delay in millis.

  retry-count - the number of previous attempts"
  [retry-count]
  (long (* 100
           (Math/pow 2 retry-count))))


(defn delay-linear
  "Calculate a linear delay in millis.

  factor      - the linear factor to use
  retry-count - the number of previous attempts"
  [factor retry-count]
  (long (* retry-count
           factor)))


(defn jitter
  "Randomly jitter a given delay.

  jitter-factor - the decimal jitter percentage, between 0 and 1
  delay         - the base delay in millis"
  [jitter-factor delay]
  (let [jit (long (* jitter-factor
                     delay))]
    (+ delay
       (.nextLong (ThreadLocalRandom/current)
                  (- jit)
                  jit))))


(defmacro with-retry
  "Evaluate body, retrying if an exception is thrown.

  spec is map containing:
    :fusebox/retry? - A predicate called after an exception to determine
                      whether body should be re-evaluated. Takes three args:
                      eval-count, exec-duration-ms, and the exception.
    :fusebox/retry-delay - A function which calculates the delay in millis to
                           wait prior to the next evaluation. Takes two args:
                           eval-count and exec-duration-ms."
  [spec & body]
  `(retry* ~spec
           (fn [] ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bulkhead                                                                   ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IBulkhead
  (-run
    [this f]
    [this f wait-timeout]
    [this f wait-timeout exec-timeout]))


(def ^{:dynamic true
       :private true} *offer-timeout* nil)


(defn ^BlockingQueue timed-offer-queue []
  (proxy [SynchronousQueue] [true]
    (offer [e]
      (if *offer-timeout*
        (proxy-super offer
                     e
                     (convert *offer-timeout*
                              :ns)
                     (unit :ns))
        (proxy-super offer e)))))


(defrecord ThreadpoolBulkhead [concurrency ^ExecutorService exec]
  IBulkhead
  (-run [this f]
    (.get (.submit ^ExecutorService exec
                   ^Callable (convey-bindings f))))
  (-run [this f wait-timeout]
    (-run this f wait-timeout nil))
  (-run [this f wait-timeout exec-timeout]
    (let [^Future fut (try (binding [*offer-timeout* wait-timeout]
                             (.submit ^ExecutorService exec
                                      ^Callable (convey-bindings f)))
                           (catch TimeoutException to
                             (throw (ex-info "fusebox timeout"
                                             {:fusebox/error :fusebox.error/offer-timeout}
                                             to))))]
      (try
        (if exec-timeout
          (.get fut
                (convert exec-timeout :ns)
                (unit :ns))
          (.get fut))
        (catch InterruptedException ie
          (.cancel fut true)
          (throw ie))
        (catch TimeoutException to
          (.cancel fut true)
          (throw (ex-info "fusebox timeout"
                          {:fusebox/error :fusebox.error/exec-timeout}
                          to)))))))


(defn threadpool-bulkhead [^long concurrency]
  (let [exec (ThreadPoolExecutor.
               concurrency
               concurrency
               0 (unit :ms)
               (timed-offer-queue)
               (let [tc (AtomicLong. -1)]
                 (reify ThreadFactory
                   (newThread [this r]
                     (doto (Thread. r)
                       (.setName (str "fusebox-bulkhead-threadpool-"
                                      (.incrementAndGet tc)))
                       (.setDaemon true))))))]
    (->ThreadpoolBulkhead concurrency
                          exec)))


(defrecord SemaphoreBulkhead [concurrency ^Semaphore s]
  IBulkhead
  (-run [this f]
    (.acquire s)
    (try (f)
         (finally
           (.release s))))
  (-run [this f offer-timeout]
    (if (.tryAcquire s
                     (convert offer-timeout :ns)
                     (unit :ns))
      (try (f)
           (finally
             (.release s)))
      (throw (ex-info "fusebox timeout"
                      {:fusebox/error :fusebox.error/offer-timeout}))))
  (-run [this f offer-timeout exec-timeout]
    (throw (UnsupportedOperationException.
             (str "SemaphoreBulkhead does not support an exec-timeout. "
                  "There is no reliable way in the JVM to guarantee that execution "
                  "halts. Using ThreadpoolBulkhead at least guarantees that "
                  "you only have N-many processes running at the same time.")))))


(defn semaphore-bulkhead [^long concurrency]
  (->SemaphoreBulkhead concurrency
                       (Semaphore. concurrency)))


(defn with-bulkhead* [{bh :fusebox/bulkhead
                       offer-to :fusebox.bulkhead/offer-timeout
                       exec-to :fusebox/exec-timeout :as spec}
                      f]
  (try
    (cond
      (and offer-to
           exec-to)
      (-run bh
            f
            offer-to
            exec-to)

      offer-to
      (-run bh f offer-to)

      :else
      (-run bh f))
    (catch Exception e
      (throw (ex-info "Error in with-bulkhead*"
                      {:fusebox/spec (pretty-spec spec)}
                      e)))))


(defmacro with-bulkhead [spec & body]
  `(with-bulkhead* ~spec
                   (^{:once true} fn* [] ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circuit Breaker                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^{:tag ScheduledThreadPoolExecutor
           :private true}
         circuit-breaker-scheduler
         (delay (let [tc (AtomicLong. -1)
                      ^ScheduledThreadPoolExecutor exec
                      (Executors/newScheduledThreadPool
                        (or (:fusebox.circuit-breaker-scheduler/num-threads edn-props)
                            1)
                        (reify ThreadFactory
                          (newThread [this r]
                            (doto (Thread. r)
                              (.setName (str "fusebox-circuit-breaker-scheduler-thread-"
                                             (.incrementAndGet tc)))
                              (.setDaemon true)))))]
                  exec)))


(defn record [spec event-type]
  (update spec
          :fusebox/record
          conj
          event-type))


(defn process-transition [{r :fusebox/record :as s}
                          {t :to
                           pred :if}]
  (when (pred s)
    (assoc s
      :fusebox/allow-pct t
      :fusebox/record (empty r))))


(defn transition [{prev :fusebox/allow-pct
                   trns :fusebox/states :as s}]
  (some (partial process-transition s)
        (get-in trns
                [prev :transitions])))


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
                   (when (and
                           ;; No intervening transition.
                           (= curr-allow prev-pct)
                           ;; No transition occurred
                           (= prev new))
                     ;; validation error, transition to self
                     (.schedule @circuit-breaker-scheduler
                                ^Callable sched
                                ^long (convert a :nanos)
                                TimeUnit/NANOSECONDS)))
                 (catch Exception e
                   (log/error "error in scheduler"
                              e
                              txn))))]
         (.schedule @circuit-breaker-scheduler
                    sched
                    ^long (convert a :nanos)
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


(defn ratio-matching? [{r :fusebox/record} pred [n d]]
  (<= n
      (count (into []
                   (comp (take d)
                         (filter pred))
                   (rseq r)))))


(defn success?
  "Is k the :success keyword?

  This exists primarily to avoid constant re-allocation of a hot function."
  [k]
  (identical? :success k))


(defn failure?
  "Is k the :failure keyword?

  This exists primarily to avoid constant re-allocation of a hot function."
  [k]
  (identical? :failure k))


(defn validate-circuit-breaker-spec [{sts :fusebox/states
                                      allow :fusebox/allow-pct
                                      record :fusebox/record}]
  (let [validate-txns (fn [s txns]
                        (mapcat (fn [{to :to
                                      pred :if :as t}]
                                  (concat
                                    (when-not pred
                                      [{:err "No :if defined for transition."
                                        :transition t
                                        :state s}])
                                    (when-not (get sts to)
                                      [{:err (str "No state definition for :to. This will "
                                                  "cause the state machine to stall.")
                                        :transition t
                                        :state s}])))
                                txns))
        validate-sched (fn [s {a :after
                               to :to :as sched}]
                         (concat
                           (when (and (= s 100)
                                      sched)
                             [{:err (str ":schedule defined for :fusebox/allow-pct 100. "
                                         "This will cause periodic unnecessary failure (but "
                                         "may be useful for dev or testing).")
                               :transition sched
                               :state s}])
                           (when-not (= s 100)
                             (concat
                               (when-not a
                                 [{:err "No :after defined for scheduled transition."
                                   :transition sched
                                   :state s}])
                               (when (and a
                                          (not (try (convert a :nanos)
                                                    (catch Exception e))))
                                 [{:err "Invalid :after definition. See the docstring for `convert."
                                   :transition sched
                                   :state s}])
                               (when-not (get sts to)
                                 [{:err (str "No state definition for :to. This will "
                                             "cause the state machine to stall in state " to ".")
                                   :transition sched
                                   :state s}])))))
        validate-state (fn [[s {sched :schedule
                                txns :transitions}]]
                         (concat
                           (validate-txns s txns)
                           (validate-sched s sched)))]
    (seq (concat (when-not allow
                   [{:err "No initial :fusebox/allow-pct defined."}])
                 (when (and allow
                            (not (get sts allow)))
                   [{:err "No state definition for initial :fusebox/allow-pct."}])
                 (when-not (instance? PersistentCircularBuffer record)
                   [{:err "Invalid type for :fusebox/record. Must be a PersistentCircularBuffer."
                     :record record
                     :found-type (type record)}])
                 (mapcat validate-state
                         sts)))))


(def defaults
  {:fusebox/allow-pct 100
   :fusebox/record (circular-buffer 128)
   :fusebox/states {100 {:transitions [{:to 0
                                        :if (fn [state]
                                              (ratio-matching? state
                                                               failure?
                                                               [5 10]))}]}

                    50 {:schedule {:to 100
                                   :after [1 :minute]
                                   :if (fn [state]
                                         (ratio-matching? state
                                                          success?
                                                          [10 10]))}
                        :transitions [{:to 0
                                       :if (fn [state]
                                             (ratio-matching? state
                                                              failure?
                                                              [3 5]))}]}

                    0 {:schedule {:to 50
                                  :after [1 :min]}}}})


(comment
  (validate-circuit-breaker-spec
    defaults))

(defn circuit-breaker
  ([]
   (circuit-breaker {}))
  ([init]
   (let [cb (atom (merge defaults
                         init))]
     (add-watch cb
                ::schedule-transition
                (fn [_k _cb old new]
                  (schedule! cb old new)))
     cb)))


(defn shutdown [cb]
  (remove-watch cb ::schedule-transition)
  nil)


(defn with-circuit-breaker* [{cb :fusebox/circuit-breaker :as spec} f]
  (let [curr @cb]
    (if (allow? curr)
      (try-interruptible
        (let [ret (f)]
          (record! cb :success)
          ret)
        (catch ExceptionInfo e
          (when-not (:fusebox/ignore? (ex-data e))
            (record! cb :failure))
          (throw e))
        (catch Exception e
          (record! cb :failure)
          (throw e)))
      (throw (ex-info "fusebox circuit breaker open"
                      {:fusebox/error :fusebox.error/circuit-breaker-open
                       :fusebox/spec (pretty-spec spec)})))))


(defmacro with-circuit-breaker [spec & body]
  `(with-circuit-breaker* ~spec
                          (^{:once true} fn* [] ~@body)))


(defmacro bulwark [spec & body]
  `(with-retry spec
     (with-circuit-breaker spec
       (with-timeout spec
         ~@body))))

(comment
  (def spec {:fusebox/circuit-breaker (circuit-breaker)
             :fusebox/bulkhead (semaphore-bulkhead 1)
             :fusebox/retry-delay (fn [retry-count exec-duration]
                                    100
                                    #_(jitter 0.80
                                              (delay-exp retry-count)))
             :fusebox/retry? (fn [retry-count exec-duration-ms last-error]
                               (and (<= retry-count 4)
                                    #_(<= exec-duration-ms
                                          (convert [30 :seconds]
                                                   :millis))))
             :fusebox.bulkhead/offer-timeout [10 :ms]
             #_#_:fusebox/exec-timeout [100 :ms]})

  (swap! (get spec :fusebox/circuit-breaker)
         assoc
         :fusebox/allow-pct 50)
  (bulwark spec
           (Thread/sleep 20))

  (with-bulkhead spec
                 (Thread/sleep 100)
                 (println "done!"))

  (pretty-spec spec)
  (shutdown (:fusebox/circuit-breaker spec))
  )
