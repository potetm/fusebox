(ns com.potetm.fusebox.cljs.circuit-breaker)


(defmacro with-circuit-breaker
  "Evaluates body which returns a promise, guarded by the provided circuit
  breaker."
  [spec & body]
  `(with-circuit-breaker* ~spec
                          (fn [] ~@body)))
