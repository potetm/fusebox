(ns com.potetm.fusebox.fallback
  (:require
    [com.potetm.fusebox.util :as util]))


(defn init
  "Initialize a fallback

  spec is a map containing:
    ::fallback - fn to invoke upon exception. Takes one arg, the exception that
                 was thrown. The return value of fn is returned to the caller."
  [{_fb ::fallback :as spec}]
  (util/assert-keys "Fallback"
                    {:req-keys [::fallback]}
                    spec)
  spec)


(defn with-fallback* [{fb ::fallback} f]
  (if-not fb
    (f)
    (try
      (f)
      (catch Exception e
        (fb e)))))


(defmacro with-fallback
  "Evaluates body, returning the return value of ::fallback upon exception."
  [spec & body]
  `(with-fallback* ~spec (^{:once true} fn* [] ~@body)))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::fallback))
