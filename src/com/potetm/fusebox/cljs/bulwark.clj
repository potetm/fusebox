(ns com.potetm.fusebox.cljs.bulwark
  (:require
    [com.potetm.fusebox.cljs.bulkhead :as bh]
    [com.potetm.fusebox.cljs.circuit-breaker :as cb]
    [com.potetm.fusebox.cljs.fallback :as fallback]
    [com.potetm.fusebox.cljs.rate-limit :as rl]
    [com.potetm.fusebox.cljs.retry :as retry]
    [com.potetm.fusebox.cljs.timeout :as to]))


(defmacro bulwark [spec & body]
  `(fallback/with-fallback ~spec
     (retry/with-retry ~spec
       (cb/with-circuit-breaker ~spec
         (bh/with-bulkhead ~spec
           (rl/with-rate-limit ~spec
             (to/with-timeout ~spec
               ~@body)))))))
