(ns com.potetm.fusebox.cljs.rate-limit)


(defmacro with-rate-limit
  "Evaluates body which returns a promise, guarded by the provided rate limiter."
  [spec & body]
  `(with-rate-limit* ~spec
                     (fn [] ~@body)))
