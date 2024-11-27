(ns com.potetm.fusebox.cljs.util)


(defn assert-keys [n {req :req-keys :as deets} spec]
  (let [ks' (into #{}
                  (remove (fn [k]
                            (get spec k)))
                  req)]
    (when (seq ks')
      (throw (ex-info (str "Invalid " n)
                      (merge deets
                             {:missing-keys ks'}))))))
