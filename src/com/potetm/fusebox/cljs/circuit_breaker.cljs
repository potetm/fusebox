(ns com.potetm.fusebox.cljs.circuit-breaker
  (:require-macros
    com.potetm.fusebox.cljs.circuit-breaker)
  (:require
    [com.potetm.fusebox.cljs.util :as util]))


(defrecord Record [record
                   record-idx
                   state
                   failed-count
                   slow-count
                   total-count
                   last-transition-at
                   half-open-count])


(defrecord RecordEntry [fails slows])


(defn time-expired?
  "Has the provided duration-ms (in millis) expired since the last state transition
  for this circuit breaker?"
  [^Record r duration-ms]
  (<= (+ (.getTime (.-last-transition-at r))
         duration-ms)
      (.getTime (js/Date.))))


(defn slow-pct
  "The percent of calls currently tracked by the circuit breaker which are slow."
  [^Record r]
  (double (/ (.-slow-count r)
             (.-total-count r))))


(defn fail-pct
  "The percent of calls currently tracked by the circuit breaker which are failed."
  [^Record r]
  (double (/ (.-failed-count r)
             (.-total-count r))))


(defn slow|fail-pct
  "The percent of calls currently tracked by the circuit breaker which are either
  slow or failed."
  [^Record r]
  (double (/ (+ (.-failed-count r)
                (.-slow-count r))
             (.-total-count r))))


(defn should-open?
  "Default implementation for determining whether to transition to a ::opened state."
  [^Record r wait-for fail-threshold slow-threshold]
  (let [s (.-state r)
        tc (.-total-count r)]
    (and (<= wait-for tc)
         (or (keyword-identical? s ::half-opened)
             (keyword-identical? s ::closed))
         (or (< fail-threshold (fail-pct r))
             (< slow-threshold (slow-pct r))))))


(defn should-close?
  "Default implementation for determining whether to transition to a ::closed state."
  [^Record r wait-for fail-threshold slow-threshold]
  (let [tc (.-total-count r)]
    (and (<= wait-for tc)
         (keyword-identical? (.-state r)
                             ::half-opened)
         (<= (fail-pct r)
             fail-threshold)
         (<= (slow-pct r)
             slow-threshold))))


(defn next-state:default
  "A default ::next-state implementation.

  * :fail-pct - The decimal threshold to use to open the breaker due to failed calls (0, 1]
  * :slow-pct - The decimal threshold to use to open the breaker due to slow calls (0, 1]
  * :wait-for-count - The number of calls to wait for after transitioning before transitioning again
  * :open->half-open-after-ms - Millis to wait before transitioning from ::opened to ::half-opened"
  [opts ^Record r]
  (let [fp (:fail-pct opts)
        sp (:slow-pct opts)
        wfc (:wait-for-count opts)
        hoa (:open->half-open-after-ms opts)]
    (cond
      (and (keyword-identical? (.-state r)
                               ::opened)
           (time-expired? r hoa))
      ::half-opened

      (should-open? r
                    (min wfc
                         (count (.-record r)))
                    fp
                    sp)
      ::opened

      (should-close? r
                     (min wfc
                          (count (.-record r)))
                     fp
                     sp)
      ::closed)))


(defn init
  "Initialize a circuit breaker.

  spec is a map containing:
    ::next-state - fn taking the current circuit breaker and returning the next
                   state or nil if no transition is necessary. See next-state:default
                   for a default implementation. Return value must be one of:
                   ::closed, ::half-opened, ::opened
    ::hist-size       - The number of calls to track
    ::half-open-tries - The number of calls to allow in a ::half-open state
    ::slow-call-ms    - Milli threshold to label a call slow
    ::success?        - A function which takes a return value and determines
                        whether it was successful. If false, a ::failure is
                        recorded."
  [{_ns ::next-state
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
  (merge {::circuit-breaker (volatile! (->Record (vec (repeat hs
                                                              (->RecordEntry 0 0)))
                                                 0
                                                 ::closed
                                                 0
                                                 0
                                                 0
                                                 (js/Date.)
                                                 0))
          ;; putting this outside the atom makes it easier to access
          ;; in with-circuit-breaker*
          ::success? (or succ? (fn [_] true))}
         spec))


(defn- half-open-allow? [lock cba]
  (let [^Record old (locking lock
                             (let [^Record r @cba
                                   hoc (.-half-open-count r)]
                               (vreset! cba
                                        (if (zero? hoc)
                                          r
                                          (assoc r :half-open-count (dec hoc))))
                               r))]
    (pos? (.-half-open-count old))))


(defn transition [spec state]
  (let [hot (::half-open-tries spec)]
    (->Record (vec (repeat (if (= state ::half-opened)
                             hot
                             (::hist-size spec))
                           (->RecordEntry 0 0)))
              0
              state
              0
              0
              0
              (js/Date.)
              (if (= state ::half-opened)
                hot
                0))))


(defn- allow? [spec]
  (let [next-state (::next-state spec)
        cba (::circuit-breaker spec)
        l (::lock spec)
        ^Record cb @cba
        s (.-state cb)]
    (boolean
      (or (keyword-identical? s ::closed)
          (and (keyword-identical? s ::half-opened)
               (half-open-allow? l cba))
          (and (when-some [s' (next-state cb)]
                 (let [^Record r (locking l
                                          (vswap! cba
                                                  (fn [^Record r]
                                                    (let [s (.-state r)]
                                                      (if (= s s')
                                                        r
                                                        (transition spec s'))))))
                       s (.-state r)]
                   (or (and (keyword-identical? s ::half-opened)
                            (half-open-allow? l cba))
                       (keyword-identical? s ::closed)))))))))


(defn record! [spec res duration-ms]
  (let [^Volatile cba (::circuit-breaker spec)
        next-state (::next-state spec)
        hist-size (::hist-size spec)
        scms (::slow-call-ms spec)
        ^Record cb @cba]
    (vreset! cba
             (let [r (.-record cb)
                   i (.-record-idx cb)
                   s (.-state cb)
                   fc (.-failed-count cb)
                   sc (.-slow-count cb)
                   tc (.-total-count cb)
                   lta (.-last-transition-at cb)
                   hoc (.-half-open-count cb)
                   ^RecordEntry re (get r i)
                   fails (if (= res ::failure)
                           1
                           0)
                   slows (if (and scms (< scms duration-ms))
                           1
                           0)
                   fc' (- (+ fc fails)
                          (.-fails re))
                   sc' (- (+ sc slows)
                          (.-slows re))
                   tc' (min (inc tc) hist-size)
                   cb' (->Record (assoc r i (->RecordEntry fails slows))
                                 (mod (inc i)
                                      hist-size)
                                 s
                                 fc'
                                 sc'
                                 tc'
                                 lta
                                 hoc)
                   s' (next-state cb')]
               (if s'
                 (transition spec s')
                 cb')))))


(defn with-circuit-breaker* [{cb ::circuit-breaker
                              succ? ::success? :as spec} f]
  (if-not cb
    (f)
    (js/Promise.
      (fn [yes no]
        (if (allow? spec)
          (let [start (.getTime (js/Date.))]
            (yes (-> (f)
                     (.then (fn [ret]
                              (record! spec
                                       (if (succ? ret)
                                         ::success
                                         ::failure)
                                       (- (.getTime (js/Date.))
                                          start))
                              ret))
                     (.catch (fn [e]
                               (record! spec
                                        ::failure
                                        (- (.getTime (js/Date.))
                                           start))
                               (throw e))))))
          (no (ex-info "fusebox circuit breaker open"
                       {:com.potetm.fusebox/error :com.potetm.fusebox.error/circuit-breaker-open
                        :com.potetm.fusebox/spec (util/pretty-spec spec)})))))))


(defn ^Record current
  "The current circuit breaker state. Useful for diagnostics or testing."
  [{cb ::circuit-breaker}]
  @cb)


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::circuit-breaker))
