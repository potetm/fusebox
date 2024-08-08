(ns com.potetm.fusebox.circuit-breaker
  (:require
    [com.potetm.fusebox :as-alias fb]
    [com.potetm.fusebox.util :as util])
  (:import
    (java.time Duration
               Instant)))


(defn time-expired?
  "Has the provided duration-ms (in millis) expired since the last state transition
  for this circuit breaker?"
  [{^Instant lta ::last-transition-at} duration-ms]
  (.isAfter (Instant/now)
            (.plusMillis lta duration-ms)))


(defn slow-pct
  "The percent of calls currently tracked by the circuit breaker which are slow."
  [r]
  (double (/ (::slow-count r)
             (::total-count r))))


(defn fail-pct
  "The percent of calls currently tracked by the circuit breaker which are failed."
  [r]
  (double (/ (::failed-count r)
             (::total-count r))))


(defn slow|fail-pct
  "The percent of calls currently tracked by the circuit breaker which are either
  slow or failed."
  [r]
  (double (/ (+ (::failed-count r)
                (::total-count r))
             (::total-count r))))


(defn should-open?
  "Default implementation for determining whether to transition to a ::opened state."
  [s
   fc
   sc
   tc
   wait-for
   fail-threshold
   slow-threshold]
  (and (<= wait-for tc)
       (or (identical? s ::half-opened)
           (identical? s ::closed))
       (or (< fail-threshold (double (/ fc tc)))
           (< slow-threshold (double (/ sc tc))))))


(defn should-close?
  "Default implementation for determining whether to transition to a ::closed state."
  [s
   fc
   sc
   tc
   wait-for
   fail-threshold
   slow-threshold]
  (and (<= wait-for tc)
       (identical? s
                   ::half-opened)
       (< (double (/ fc tc)) fail-threshold)
       (< (double (/ sc tc)) slow-threshold)))


(defn next-state:default
  "A default ::next-state implementation.

  * :fail-pct - The decimal threshold to use to open the breaker due to failed calls (0, 1]
  * :slow-pct - The decimal threshold to use to open the breaker due to slow calls (0, 1]
  * :wait-for-count - The number of calls to wait for after transitioning before transitioning again
  * :open->half-open-after-ms - Millis to wait before transitioning from ::opened to ::half-open"
  [opts cb]
  (let [fp (:fail-pct opts)
        sp (:slow-pct opts)
        wfc (:wait-for-count opts)
        hoa (:open->half-open-after-ms opts)
        s (::state cb)
        fc (::failed-count cb)
        sc (::slow-count cb)
        tc (::total-count cb)]
    (cond
      (and (identical? s ::opened)
           (time-expired? cb hoa))
      ::half-opened

      (should-open? s fc sc tc wfc fp sp)
      ::opened

      (should-close? s fc sc tc wfc fp sp)
      ::closed)))


(defn init
  "Initialize a circuit breaker.

  spec is a map containing:
    ::next-state - fn taking the current circuit breaker and returning the next
                   state or nil if no transition is necessary. See next-state:default
                   for a default implementation. Return value must be one of:
                   ::closed, ::half-open, ::open
    ::hist-size       - The number of calls to track
    ::half-open-tries - The number of calls to allow in a ::half-open state
    ::slow-call-ms    - Milli threshold to label a call slow
    ::success?        - A function which takes a return value and determines
                        whether it was successful. If false, a ::failure is
                        recorded."
  [{ns ::next-state
    hs ::hist-size
    _hot ::half-open-tries
    _scm ::slow-call-ms
    succ? ::success? :as spec}]
  (util/assert-keys "Circuit Breaker"
                    {:req-keys [::next-state
                                ::hist-size
                                ::half-open-tries
                                ::slow-call-ms]
                     :opt-keys []}
                    spec)
  {::circuit-breaker (atom (merge {::record (vec (repeat hs
                                                         {::fails 0
                                                          ::slows 0}))
                                   ::record-idx 0
                                   ::state ::closed
                                   ::slow-count 0
                                   ::failed-count 0
                                   ::total-count 0}
                                  (dissoc spec
                                          ::next-state
                                          ::success?)))
   ;; putting this outside the atom makes it easier to access
   ;; in with-circuit-breaker*
   ::next-state ns
   ::success? (or succ? (fn [_] true))})


(defn- half-open-allow? [cba]
  (let [[{old ::half-open-count} _new]
        (swap-vals! cba
                    (fn [{hoc ::half-open-count :as s}]
                      (if (zero? hoc)
                        s
                        (assoc s ::half-open-count (dec hoc)))))]
    (pos? old)))


(defn- transition [prev state]
  (cond-> (assoc prev
            ::record (vec (repeat (if (= state ::half-opened)
                                    (::half-open-tries prev)
                                    (::hist-size prev))
                                  {::fails 0
                                   ::slows 0}))
            ::record-idx 0
            ::state state
            ::slow-count 0
            ::failed-count 0
            ::total-count 0
            ::last-transition-at (Instant/now))

    (= state ::half-opened)
    (assoc
      ::half-open-count (::half-open-tries prev))))


(defn- allow? [cba next-state]
  (let [{s ::state :as cb} @cba]
    (boolean
      (or (= s ::closed)
          (and (= s ::half-opened)
               (half-open-allow? cba))
          (and (when-some [s' (next-state cb)]
                 (let [{s ::state} (swap! cba
                                          (fn [{s ::state :as cb}]
                                            (if (= s s')
                                              cb
                                              (transition cb s'))))]
                   (or (and (= s ::half-opened)
                            (half-open-allow? cba))
                       (= s ::closed)))))))))


(defn record! [cba next-state res duration-ms]
  (swap! cba
         ;; Don't fall into the varargs arity of swap!
         (fn [{r ::record
               i ::record-idx
               rs ::hist-size
               sc ::slow-count
               fc ::failed-count
               tc ::total-count
               scms ::slow-call-ms :as cb}]
           (let [{prev-f ::fails
                  prev-s ::slows} (get r i)
                 fails (if (= res ::failure)
                         1
                         0)
                 slows (if (and scms (< scms duration-ms))
                         1
                         0)
                 fc' (- (+ fc fails)
                        prev-f)
                 sc' (- (+ sc slows)
                        prev-s)
                 tc' (min (inc tc) rs)
                 cb' (-> cb
                         (assoc ::record (assoc r
                                           i {::fails fails
                                              ::slows slows}))
                         (assoc ::record-idx (mod (inc i) rs))
                         (assoc ::failed-count fc')
                         (assoc ::slow-count sc')
                         (assoc ::total-count tc'))
                 s' (next-state cb')]
             (cond-> cb'
               s' (transition s'))))))


(defn with-circuit-breaker* [{cb ::circuit-breaker
                              ns ::next-state
                              succ? ::success? :as spec} f]
  (if-not cb
    (f)
    (if (allow? cb ns)
      (let [start (Instant/now)]
        (util/try-interruptible
          (let [ret (f)]
            (record! cb
                     ns
                     (if (succ? ret)
                       ::success
                       ::failure)
                     (.toMillis (Duration/between start
                                                  (Instant/now))))
            ret)
          (catch Exception e
            (record! cb
                     ns
                     ::failure
                     (.toMillis (Duration/between start
                                                  (Instant/now))))
            (throw e))))
      (throw (ex-info "fusebox circuit breaker open"
                      {::fb/error ::circuit-breaker-open
                       ::fb/spec (util/pretty-spec spec)})))))


(defmacro with-circuit-breaker
  "Evaluates body, guarded by the provided circuit breaker."
  [spec & body]
  `(with-circuit-breaker* ~spec
                          (^{:once true} fn* [] ~@body)))


(defn current
  "The current circuit breaker state. Useful for diagnostics or testing."
  [{cb ::circuit-breaker}]
  @cb)

(defn shutdown [spec])
