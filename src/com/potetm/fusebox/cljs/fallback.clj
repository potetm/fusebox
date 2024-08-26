(ns com.potetm.fusebox.cljs.fallback)


(defmacro with-fallback
  "Evaluates body which returns a promise, returning the return value of
  ::fallback upon exception."
  [spec & body]
  `(with-fallback* ~spec
                   (fn [] ~@body)))
