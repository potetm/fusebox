(ns com.potetm.promise
  (:refer-clojure :exclude [await]))


(defmacro await [bindings & body]
  (cond
    (not (vector? bindings))
    (throw (ex-info "js-await requires a vector binding form"
                    {:bindings bindings}))

    (not (even? (count bindings)))
    (throw (ex-info "js-await requires an even number of binding forms"
                    {:bindings bindings}))

    (= 2 (count bindings))
    (let [[n p] bindings
          last-expr (last body)

          [body catch]
          (if (and (seq? last-expr)
                   (= 'catch (first last-expr)))
            [(butlast body) (next last-expr)]
            [body nil])]
      `(-> ~p
           ~@(when body
               [`(.then (fn [~n] ~@body))])
           ~@(when catch
               (let [[n & body] catch]
                 [`(.catch (fn [~n] ~@body))]))))
    :else
    (let [[n p] bindings]
      `(.then ~p
              (fn [~n]
                (await ~(subvec bindings 2) ~@body))))))
