(ns com.potetm.fusebox.cljs.semaphore
  (:refer-clojure :exclude [resolve]))


(def microtask (js/Promise.resolve))


(defprotocol ITask
  (reject [this])
  (resolve [this]))


(deftype Task [yes no ^:mutable pending?]
  ITask
  (resolve [this]
    (when pending?
      (set! pending? false)
      (yes nil)))
  (reject [this]
    (when pending?
      (set! pending? false)
      (no (ex-info "fusebox timeout"
                   {:com.potetm.fusebox/error :com.potetm.fusebox.error/exec-timeout})))))


(defprotocol ISemaphore
  (acquire [this timeout-ms])
  (release [this]
           [this permits])
  (drain [this]))


(deftype Semaphore [^:mutable permits ^:mutable tasks]
  ISemaphore
  (acquire [this timeout-ms]
    (js/Promise. (fn [yes no]
                   (if (pos? permits)
                     (do (set! permits (dec permits))
                         (yes nil))
                     (let [t (->Task yes no true)]
                       (do (set! tasks (conj tasks t))
                           (js/setTimeout (fn []
                                            (reject t))
                                          timeout-ms)))))))


  (release [this]
    (loop [tsks (pop tasks)
           t (peek tasks)]
      (if-not t
        (set! permits (inc permits))
        (if (.-pending? ^Task t)
          (do (set! tasks tsks)
              (.then microtask
                     (fn []
                       (resolve t))))
          (recur (pop tsks)
                 (peek tsks)))))
    nil)


  (release [this perms]
    (dotimes [_ perms]
      (release this)))


  (drain [this]
    (set! permits 0)))


(defn semaphore [permits]
  (->Semaphore permits #queue[]))
