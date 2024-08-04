(ns com.potetm.fusebox
  (:refer-clojure :exclude [memoize])
  (:import
    (clojure.lang Var)
    (java.time Duration Instant)
    (java.util.concurrent ConcurrentHashMap Executors
                          ExecutorService
                          Semaphore
                          ThreadFactory
                          ThreadLocalRandom
                          TimeoutException
                          TimeUnit)
    (java.util.concurrent.atomic AtomicLong)
    (java.util.function Function)))


(set! *warn-on-reflection* true)


(defn class-for-name [n]
  (try
    (Class/forName n)
    (catch ClassNotFoundException _)))


(def virtual-threadpool
  (when (class-for-name "java.lang.VirtualThread")
    (eval '(Executors/newThreadPerTaskExecutor (-> (Thread/ofVirtual)
                                                   (.name "fusebox-thread-" 1)
                                                   (.factory))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unit Conversion                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def unit*
  (merge (zipmap [:ns :nano :nanos :nanosecond :nanoseconds]
                 (repeat TimeUnit/NANOSECONDS))

         (zipmap [:µs :us :micro :micros :microsecond :microseconds]
                 (repeat TimeUnit/MICROSECONDS))

         (zipmap [:ms :milli :millis :millisecond :milliseconds]
                 (repeat TimeUnit/MILLISECONDS))

         (zipmap [:s :sec :secs :second :seconds]
                 (repeat TimeUnit/SECONDS))

         (zipmap [:m :min :mins :minute :minutes]
                 (repeat TimeUnit/MINUTES))

         (zipmap [:h :hr :hour :hours]
                 (repeat TimeUnit/HOURS))

         (zipmap [:d :day :days]
                 (repeat TimeUnit/DAYS))))


(defn unit ^TimeUnit [kw]
  (unit* kw))


(defn convert ^long [[v orig] target]
  (.convert (unit target)
            v
            (unit orig)))


(defn ms ^long [unit]
  (convert unit :ms))

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


(defn pretty-spec
  ([spec]
   (dissoc spec
           ::circuit-breaker
           ::retry-delay
           ::retry?
           ::bulkhead)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timeout                                                                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^{:doc "An unbound threadpool used for reliably timing out calls."
           :private true}
  timeout-threadpool
  (delay (or virtual-threadpool
             (Executors/newCachedThreadPool (let [tc (AtomicLong. -1)]
                                              (reify ThreadFactory
                                                (newThread [this r]
                                                  (doto (Thread. r)
                                                    (.setName (str "fusebox-thread-"
                                                                   (.incrementAndGet tc)))
                                                    (.setDaemon true)))))))))

(defn timeout* [{to ::exec-timeout
                 intr? ::interrupt?
                 :or {intr? true}
                 :as spec}
                f]
  (let [fut (.submit ^ExecutorService @timeout-threadpool
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
                        {::error ::error-exec-timeout
                         ::spec (pretty-spec spec)}))))))


(defmacro with-timeout
  "Evaluates body, aborting if it lasts longer than specified.

  spec is map containing:
    ::timeout - a time unit tuple (e.g. [10 :seconds])"
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


(defn retry* [{retry? ::retry?
               delay ::retry-delay :as spec}
              f]
  (let [start (System/currentTimeMillis)
        exec-dur #(- (System/currentTimeMillis)
                     start)]
    (loop [n 0]
      (let [[ret v] (try-interruptible
                      [::success (binding [*retry-count* n
                                           *exec-duration-ms* (exec-dur)]
                                   (f))]
                      (catch Exception e
                        [::error e]))]
        (case ret
          ::success v
          ::error (let [ed (exec-dur)
                        n' (inc n)]
                    (if (retry? n'
                                ed
                                v)
                      (do (Thread/sleep (convert (delay n'
                                                        (exec-dur))
                                                 :ms))
                          (recur n'))
                      (throw (ex-info "fusebox retries exhausted"
                                      {::error ::error-retries-exhausted
                                       ::num-retries n
                                       ::exec-duration-ms ed
                                       ::spec (pretty-spec spec)}
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
    ::retry? - A predicate called after an exception to determine
               whether body should be re-evaluated. Takes three args:
               eval-count, exec-duration-ms, and the exception.
    ::retry-delay - A function which calculates the delay in millis to
                    wait prior to the next evaluation. Takes two args:
                    eval-count and exec-duration-ms."
  [spec & body]
  `(retry* ~spec
           (fn [] ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rate Limit                                                                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rate-limiter [{n ::bucket-size
                     p ::period-ms :as opts}]
  (let [sem (Semaphore. n)
        release (fn []
                  (let [ns (convert [p :ms] :ns)]
                    (while true
                      (Thread/sleep (Duration/ofNanos ns))
                      (.release sem
                                ;; This isn't atomic, but the worst case
                                ;; isn't terrible: might be a couple of tokens
                                ;; short for one cycle.
                                (- n (.availablePermits sem))))))]
    (merge opts
           {::sem sem
            ::bg-thread
            (if (class-for-name "java.lang.VirtualThread")
              (eval
                '(-> (Thread/ofVirtual)
                     (.name "rate-limiter-bg-thread")
                     (.start release)))
              (doto (Thread. ^Runnable release)
                (.setName "rate-limiter-bg-thread")
                (.setDaemon true)
                (.start)))})))


(defn shutdown [{bg ::bg-thread}]
  (.interrupt ^Thread bg))


(defn rate-limit* [{^Semaphore s ::sem
                    to ::timeout-ms}
                   f]
  (when s
    (when-not (.tryAcquire s
                           to
                           TimeUnit/MILLISECONDS)
      (throw (ex-info "Timeout waiting for rate limiter"
                      {::timeout-ms to}))))
  (f))


(defmacro with-rate-limit [spec & body]
  `(rate-limit* ~spec
                (^{:once true} fn* [] ~@body)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bulkhead                                                                   ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bulkhead [^long concurrency]
  {::conc concurrency
   ::sem (Semaphore. concurrency)})


(defn shutdown-bulkhead [{^Semaphore s ::sem}]
  ;; Don't allow any more processes to acquire any more permits
  (.drainPermits s))


(defn with-bulkhead* [{^Semaphore s ::sem
                       to ::timeout :as spec}
                      f]
  (when s
    (if (.tryAcquire s
                     to
                     TimeUnit/MILLISECONDS)
      (try (f)
           (finally
             (.release s)))
      (throw (ex-info "fusebox timeout"
                      {::error ::error-offer-timeout
                       ::spec (pretty-spec spec)})))))


(defmacro with-bulkhead [spec & body]
  `(with-bulkhead* ~spec
                   (^{:once true} fn* [] ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circuit Breaker                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn time-expired? [{^Instant lta :last-transition-at} duration-ms]
  (.isAfter (Instant/now)
            (.plusMillis lta duration-ms)))


(defn slow-pct [{sc :slow-count
                 tc :total-count}]
  (double (/ sc tc)))


(defn fail-pct [{fc :failed-count
                 tc :total-count}]
  (double (/ fc tc)))


(defn slow|fail-pct [{fc :failed-count
                      sc :slow-count
                      tc :total-count}]
  (double (/ (+ fc sc)
             tc)))


(defn should-open? [{s :state
                     tc :total-count :as cb}
                    fail-threshold
                    slow-threshold]
  (and (pos? tc)
       (or (= s :half-opened)
           (= s :closed))
       (< fail-threshold (fail-pct cb))
       (< slow-threshold (slow-pct cb))))


(defn should-close? [{s :state
                      tc :total-count :as cb}
                     wait-for
                     fail-threshold
                     slow-threshold]
  (and (pos? tc)
       (= s :half-opened)
       (<= wait-for tc)
       (< (fail-pct cb) fail-threshold)
       (< (slow-pct cb) slow-threshold)))


(defn default-next-state [{fp :fail-pct
                           sp :slow-pct
                           howf :half-open-wait-for-count
                           hoa :open->half-open-after-ms}
                          {s :state :as cb}]
  (cond
    (and (= s :opened)
         (time-expired? cb hoa))
    :half-opened

    (should-open? cb fp sp)
    :opened

    (should-close? cb howf fp sp)
    :closed))


(defn circuit-breaker [{rs ::hist-size
                        succ? ::success? :as spec}]
  {::circuit-breaker (atom (merge {:record (vec (repeat rs
                                                        {:fails 0
                                                         :slows 0}))
                                   :record-idx 0
                                   :state :closed
                                   :slow-count 0
                                   :failed-count 0
                                   :total-count 0}
                                  spec))
   ;; putting this here makes it easier to access in with-circuit-breaker*
   ::success? (or succ? (fn [_] true))})


(defn half-open-allow? [cba]
  (let [[{old :half-open-count} _new]
        (swap-vals! cba
                    (fn [{hoc :half-open-count :as s}]
                      (if (zero? hoc)
                        s
                        (assoc s :half-open-count (dec hoc)))))]
    (pos? old)))


(defn transition [prev state]
  (cond-> (assoc prev
            :record (vec (repeat (if (= state :half-opened)
                                   (::half-open-tries prev)
                                   (::hist-size prev))
                                 {:fails 0
                                  :slows 0}))
            :record-idx 0
            :state state
            :slow-count 0
            :failed-count 0
            :total-count 0
            :last-transition-at (Instant/now))

    (= state :half-opened)
    (assoc
      :half-open-count (::half-open-tries prev))))


(defn allow? [cba]
  (let [{s :state
         next-state ::next-state :as cb} @cba]
    (boolean
      (or (= s :closed)
          (and (= s :half-opened)
               (half-open-allow? cba))
          (and (when-some [s' (next-state cb)]
                 (let [{s :state} (swap! cba
                                         (fn [{s :state :as cb}]
                                           (if (= s s')
                                             cb
                                             (transition cb s'))))]
                   (or (and (= s :half-opened)
                            (half-open-allow? cba))
                       (= s :closed)))))))))


;; defn so we avoid allocation another anonymous function on every call
(defn record!* [{r :record
                 i :record-idx
                 rs ::hist-size
                 sc :slow-count
                 fc :failed-count
                 tc :total-count
                 scms ::slow-call-ms
                 next-state ::next-state :as cb} res duration-ms]
  (let [{prev-f :fails
         prev-s :slows} (get r i)
        fails (if (= res :failure)
                1
                0)
        slows (if (< scms duration-ms)
                1
                0)
        fc' (- (+ fc fails)
               prev-f)
        sc' (- (+ sc slows)
               prev-s)
        tc' (min (inc tc) rs)
        cb' (assoc cb
              :record (assoc r
                        i {:fails fails
                           :slows slows})
              :record-idx (mod (inc i) rs)
              :failed-count fc'
              :slow-count sc'
              :total-count tc')
        s' (next-state cb')]
    (cond-> cb'
      s' (transition s'))))


(defn record! [cba res duration-ms]
  (swap! cba
         record!*
         res
         duration-ms))


(defn with-circuit-breaker* [{cb ::circuit-breaker
                              succ? ::success? :as spec} f]
  (if (allow? cb)
    (let [start (Instant/now)]
      (try-interruptible
        (let [ret (f)]
          (record! cb
                   (if (succ? ret)
                     :success
                     :failure)
                   (.toMillis (Duration/between start
                                                (Instant/now))))
          ret)
        (catch Exception e
          (record! cb
                   :failure
                   (.toMillis (Duration/between start
                                                (Instant/now))))
          (throw e))))
    (throw (ex-info "fusebox circuit breaker open"
                    {::error ::error-circuit-breaker-open
                     ::spec (pretty-spec spec)}))))


(defmacro with-circuit-breaker [spec & body]
  `(with-circuit-breaker* ~spec
                          (^{:once true} fn* [] ~@body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memoize                                                                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn memoize
  ([f]
   (memoize f nil))
  ([f default]
   {::chm (ConcurrentHashMap.)
    ::fn f
    ::default default}))


(defn memo [{^ConcurrentHashMap chm ::chm
             f ::fn
             d ::default} & args]
  (or (.computeIfAbsent chm
                        args
                        (reify Function
                          (apply [this args]
                            (apply f args))))
      d))

(comment
  @(def cb
     (circuit-breaker {::next-state (partial default-next-state
                                             {:fail-pct 0.5
                                              :half-open-wait-for-count 10
                                              :open->half-open-after-ms 10000})
                       ::half-open-tries 10
                       ::hist-size 10
                       ::slow-call-ms 5000}))
  @cb
  (allow? cb)
  ((::next-state @cb) @cb)
  (record! cb :success 1000)
  (record! cb :failure 1000)

  (def spec {::circuit-breaker (circuit-breaker)
             ::bulkhead (threadpool-bulkhead 1) #_(semaphore-bulkhead 1)
             ::retry-delay (fn [retry-count exec-duration]
                             100
                             #_(jitter 0.80
                                       (delay-exp retry-count)))
             ::retry? (fn [retry-count exec-duration-ms last-error]
                        (and (<= retry-count 4)
                             #_(<= exec-duration-ms
                                   (convert [30 :seconds]
                                            :millis))))
             ::bulkhead-offer-timeout [10 :ms]
             ::exec-timeout [100 :ms]})

  (shutdown-spec spec [1 :sec])

  (circuit-breaker)
  (::circuit-breaker spec)
  (swap! (get spec ::circuit-breaker)
         assoc
         ::allow-pct 50)
  (bulwark spec
           (Thread/sleep 20))

  (macroexpand-1
    '(bulwark spec
              (Thread/sleep 20)))

  (doseq [f [(future (with-bulkhead spec
                       (Thread/sleep 100)
                       (println "done!")))
             (future (with-bulkhead spec
                       (Thread/sleep 100)
                       (println "done!")))]]
    @f)

  (pretty-spec spec)
  (shutdown-circuit-breaker (::circuit-breaker spec))
  )
