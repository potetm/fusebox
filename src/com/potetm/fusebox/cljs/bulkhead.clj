(ns com.potetm.fusebox.cljs.bulkhead)


(defmacro with-bulkhead
  "Evaluates body which returns a promise, guarded by the provided bulkhead."
  [spec & body]
  `(with-bulkhead* ~spec
                   (fn [] ~@body)))
