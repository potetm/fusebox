(ns com.potetm.fusebox.alpha-specs
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as sgen]
    [com.potetm.fusebox.bulkhead :as-alias bh]
    [com.potetm.fusebox.circuit-breaker :as-alias cb]
    [com.potetm.fusebox.fallback :as-alias fallback]
    [com.potetm.fusebox.memoize :as-alias memo]
    [com.potetm.fusebox.rate-limit :as-alias rl]
    [com.potetm.fusebox.retry :as-alias retry]
    [com.potetm.fusebox.timeout :as-alias to])
  (:import
    (java.time Instant)))


(s/def ::non-neg
  (s/and integer?
         (comp not neg?)))
(s/def ::pos-int
  (s/and integer?
         pos?))
(s/def ::ex
  #(instance? Exception %))


;; Bulkhead
(s/def ::bh/concurrency ::pos-int)
(s/def ::bh/timeout-ms ::pos-int)
(s/def ::bulkhead (s/keys :req [::bh/concurrency
                                ::bh/timeout-ms]))


;; Circuit Breaker
(s/def ::cb/record (s/coll-of ::record-elements))
(s/def ::record-elements (s/keys :req [::cb/fails
                                       ::cb/slows]))
(s/def ::cb/fails ::non-neg)
(s/def ::cb/slows ::non-neg)
(s/def ::cb/record-idx ::non-neg)
(s/def ::cb/state #{::cb/opened
                    ::cb/half-opened
                    ::cb/closed})
(s/def ::cb/failed-count ::non-neg)
(s/def ::cb/slow-count ::non-neg)
(s/def ::cb/total-count ::non-neg)
(s/def ::cb/last-transition-at
  (s/with-gen #(instance? Instant %)
              (fn []
                (sgen/fmap #(.toInstant %)
                           (s/gen inst?)))))
(s/def ::cb/half-open-count ::non-neg)

(s/def ::cb/hist-size ::pos-int)
(s/def ::cb/next-state (s/fspec :args (s/cat :state (s/keys :req [::cb/record
                                                                  ::cb/record-idx
                                                                  ::cb/state
                                                                  ::cb/failed-count
                                                                  ::cb/slow-count
                                                                  ::cb/total-count
                                                                  ::cb/last-transition-at]
                                                            :opt [::cb/half-open-count]))
                                :ret (s/nilable ::cb/state)))
(s/def ::cb/half-open-tries ::pos-int)
(s/def ::cb/slow-call-ms ::pos-int)
(s/def ::cb/success? (s/fspec :args (s/cat :val any?)
                              :ret boolean?))
(s/def ::circuit-breaker (s/keys :req [::cb/hist-size
                                       ::cb/next-state
                                       ::cb/half-open-tries]
                                 :opt [::cb/slow-call-ms
                                       ::cb/success?]))


;; Fallback
(s/def ::fallback/fallback (s/fspec :args (s/cat :ex ::ex)
                                    :ret any?))
(s/def ::fallback (s/keys :req [::fallback/fallback]))


;; Memo
(s/def ::memo/fn fn?)
(s/def ::memoize (s/keys :req [::memo/fn]))


;; Rate Limit
(s/def ::rl/bucket-size ::pos-int)
(s/def ::rl/period-ms ::pos-int)
(s/def ::rl/wait-timeout-ms ::pos-int)
(s/def ::rate-limit (s/keys :req [::rl/bucket-size
                                  ::rl/period-ms
                                  ::rl/wait-timeout-ms]))


;; Retry
(s/def ::retry/retry? (s/fspec :args (s/cat :eval-count ::pos-int
                                            :exec-duration ::pos-int
                                            :ex ::ex)
                               :ret boolean?))
(s/def ::retry/delay (s/fspec :args (s/cat :eval-count ::pos-int
                                           :exec-duration ::pos-int
                                           :ex ::ex)
                              :ret ::non-neg))
(s/def ::retry/success? (s/fspec :args (s/cat :val any?)
                                 :ret boolean?))
(s/def ::retry (s/keys :req [::retry/retry?
                             ::retry/delay]
                       :opt [::retry/success?]))


;; Timeout
(s/def ::to/timeout-ms ::pos-int)
(s/def ::to/interrupt? boolean?)
(s/def ::timeout (s/keys :req [::to/timeout-ms]
                         :opt [::to/interrupt?]))

(comment
  (s/valid? ::timeout
            {::to/timeout-ms 1000
             ::to/interrupt? true})
  (s/valid? ::circuit-breaker
            {::cb/next-state (constantly nil)
             ::cb/hist-size 10
             ::cb/half-open-tries 3
             ::cb/slow-call-ms 10}))
