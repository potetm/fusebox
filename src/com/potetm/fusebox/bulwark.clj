(ns com.potetm.fusebox.bulwark
  (:require
    [com.potetm.fusebox.bulkhead :as bh]
    [com.potetm.fusebox.circuit-breaker :as cb]
    [com.potetm.fusebox.fallback :as fallback]
    [com.potetm.fusebox.rate-limit :as rl]
    [com.potetm.fusebox.retry :as retry]
    [com.potetm.fusebox.timeout :as to]))


(defmacro bulwark [spec & body]
  `(fallback/with-fallback ~spec
     (retry/with-retry ~spec
       (cb/with-circuit-breaker ~spec
         (bh/with-bulkhead ~spec
           (rl/with-rate-limit ~spec
             (to/with-timeout ~spec
               ~@body)))))))


(defn shutdown [spec]
  (bh/shutdown spec)
  (cb/shutdown spec)
  (fallback/shutdown spec)
  (rl/shutdown spec)
  (retry/shutdown spec)
  (to/shutdown spec))


(defn disable [spec]
  (-> spec
      (bh/disable)
      (cb/disable)
      (fallback/disable)
      (rl/disable)
      (retry/disable)
      (to/disable)))
