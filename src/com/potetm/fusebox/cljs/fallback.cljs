(ns com.potetm.fusebox.cljs.fallback
  (:require-macros
    com.potetm.fusebox.cljs.fallback)
  (:require
    [com.potetm.fusebox.cljs.util :as util]))


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
    (-> (f)
        (.catch (fn [e]
                  (fb e))))))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::fallback))
