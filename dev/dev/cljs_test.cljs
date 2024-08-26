(ns dev.cljs-test
  (:require
    [cljs.test :as t]
    [shadow.dom :as dom]
    [cljs-test-display.core :as ctd]))

(comment
  (dom/append [:div#test-root])
  (require 'com.potetm.fusebox.cljs.bulkhead-test
           'com.potetm.fusebox.cljs.bulwark-test
           'com.potetm.fusebox.cljs.circuit-breaker-test
           'com.potetm.fusebox.cljs.fallback-test
           'com.potetm.fusebox.cljs.memoize-test
           'com.potetm.fusebox.cljs.rate-limit-test
           'com.potetm.fusebox.cljs.registry-test
           'com.potetm.fusebox.cljs.timeout-test)
  (t/run-tests (ctd/init! "test-root")
               'com.potetm.fusebox.cljs.bulkhead-test
               'com.potetm.fusebox.cljs.bulwark-test
               'com.potetm.fusebox.cljs.circuit-breaker-test
               'com.potetm.fusebox.cljs.fallback-test
               'com.potetm.fusebox.cljs.memoize-test
               'com.potetm.fusebox.cljs.rate-limit-test
               'com.potetm.fusebox.cljs.registry-test
               'com.potetm.fusebox.cljs.timeout-test)
  )


