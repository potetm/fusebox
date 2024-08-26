(ns com.potetm.fusebox.cljs.retry)


(defmacro with-retry
  "Evaluates body which returns a promise, retrying according to the provided
  retry spec."
  [bindings|spec & [spec|body & body :as b]]
  (if (vector? bindings|spec)
    `(with-retry* ~spec|body
                  (fn ~bindings|spec ~@body))
    `(with-retry* ~bindings|spec
                  (fn [count# duration#] ~@b))))
