(ns com.potetm.fusebox.cljs.bulkhead
  (:require-macros
    com.potetm.fusebox.cljs.bulkhead)
  (:require
    [com.potetm.fusebox.cljs.semaphore :as sem]
    [com.potetm.fusebox.cljs.util :as util]))


(defn init
  "Initialize a bulkhead (i.e. concurrency limiter).

  spec is a map containing:
    ::concurrency     - the integer number of concurrent callers to allow
    ::wait-timeout-ms - max millis a thread will wait to enter bulkhead"
  [{c ::concurrency
    _wt ::wait-timeout-ms :as spec}]
  (util/assert-keys "Bulkhead"
                    {:req-keys [::concurrency
                                ::wait-timeout-ms]}
                    spec)
  (merge {::sem (sem/semaphore c)}
         spec))


(defn with-bulkhead* [{s ::sem
                       to ::wait-timeout-ms}
                      f]
  (if-not s
    (f)
    (-> (sem/acquire s to)
        (.then (fn [_]
                 (-> (f)
                     (.finally (fn [_]
                                 (sem/release s)))))))))


(defn shutdown [{s ::sem}]
  ;; Don't allow any more processes to acquire any more permits
  (sem/drain s)
  nil)


(defn disable [spec]
  (dissoc spec ::sem))
