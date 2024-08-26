(ns com.potetm.fusebox.cljs.bulwark
  (:require-macros
    com.potetm.fusebox.cljs.bulwark)
  (:require
    [com.potetm.fusebox.cljs.bulkhead :as bh]
    [com.potetm.fusebox.cljs.circuit-breaker :as cb]
    [com.potetm.fusebox.cljs.fallback :as fallback]
    [com.potetm.fusebox.cljs.rate-limit :as rl]
    [com.potetm.fusebox.cljs.retry :as retry]
    [com.potetm.fusebox.cljs.timeout :as to]))


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
