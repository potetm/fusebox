(ns com.potetm.fusebox.fallback)


(defn with-fallback* [{fb ::fallback} f]
  (try (f)
       (catch Exception e
         (if fb
           (fb e)
           (throw e)))))


(defmacro with-fallback [spec & body]
  `(with-fallback* ~spec (^{:once true} fn* [] ~@body)))
