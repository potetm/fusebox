(ns com.potetm.fusebox.registry)


(def registry
  (atom {}))


(defn register! [k spec]
  (swap! registry assoc k spec)
  nil)


(defn un-register! [k spec]
  (swap! registry dissoc k spec)
  nil)
