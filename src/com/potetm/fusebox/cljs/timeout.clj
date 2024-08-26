(ns com.potetm.fusebox.cljs.timeout)


(defmacro with-timeout
  "Evaluates body which returns a promise, throwing ExceptionInfo if lasting
  longer than specified.

  spec is the return value of init."
  [bindings|spec & [spec|body & body :as b]]
  (if (vector? bindings|spec)
    `(with-timeout* ~spec|body
                    (fn ~bindings|spec ~@body))
    `(with-timeout* ~bindings|spec
                    (fn [abort-controller#] ~@b))))
