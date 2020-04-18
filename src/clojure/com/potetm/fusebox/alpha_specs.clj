(ns com.potetm.fusebox.alpha-specs
  (:require [clojure.spec.alpha :as s]
            [com.potetm.fusebox :as fb])
  (:import (com.potetm.fusebox PersistentCircularBuffer)))

(s/def ::pos-int
  (s/and integer?
         pos?))

(s/def ::fb/time-unit
  (set (keys fb/unit*)))

(s/def ::fb/duration
  (s/and vector?
         (s/cat :amount ::pos-int
                :unit ::fb/time-unit)))

;; timeout
(s/def ::fb/exec-timeout ::fb/duration)
(s/def ::fb/interrupt? boolean?)

;; retry
(s/def ::fb/retry?
  (s/fspec :args (s/cat :eval-count ::pos-int
                        :duration-ms ::pos-int
                        :ex #(instance? Exception %))
           :ret boolean?))
(s/def ::fb/retry-delay
  (s/fspec :args (s/cat :eval-count ::pos-int
                        :duration-ms ::pos-int)
           :ret ::fb/duration))

;; bulkhead
(s/def ::fb/bulkhead #(satisfies? fb/IBulkhead %))
(s/def ::fb/bulkhead-offer-timeout ::fb/duration)


;; circuit breaker
(s/def ::fb/allow-pct
  (s/and integer?
         #(<= 0 % 100)))
(s/def ::fb/record #(instance? PersistentCircularBuffer %))
(s/def ::fb/states
  (s/and map?
         (comp empty? fb/validate-circuit-breaker-states)))
