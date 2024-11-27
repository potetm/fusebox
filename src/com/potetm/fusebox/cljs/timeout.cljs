(ns com.potetm.fusebox.cljs.timeout
  (:require-macros
    com.potetm.fusebox.cljs.timeout)
  (:require
    [com.potetm.fusebox.cljs.util :as util]))


(defn init
  "Initialize a Timeout.

  spec is a map containing:
    ::timeout-ms - millis to wait before timing out"
  [{_to ::timeout-ms :as spec}]
  (util/assert-keys "Timeout"
                    {:req-keys [::timeout-ms]}
                    spec)
  spec)


(defn with-timeout* [{to ::timeout-ms} f]
  (if-not to
    (f nil)
    (let [ac (js/AbortController.)
          ref (volatile! nil)]
      (-> (js/Promise.race (array (f ac)
                                  (js/Promise. (fn [yes no]
                                                 (vreset! ref
                                                          (js/setTimeout (fn []
                                                                           (.abort ac)
                                                                           (no (ex-info "fusebox timeout"
                                                                                        {:com.potetm.fusebox/error :com.potetm.fusebox.error/exec-timeout
                                                                                         ::timeout-ms to})))
                                                                         to))))))
          (.finally (fn []
                      (js/clearTimeout ref)))))))


(defn shutdown [spec])


(defn disable [spec]
  (dissoc spec ::timeout-ms))
