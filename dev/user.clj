(ns user
  (:require
    [clojure.java.io :as io]
    [shadow.cljs.devtools.server :as server]
    [shadow.cljs.devtools.api :as shadow])
  (:import
    (java.awt Toolkit)
    (java.awt.datatransfer StringSelection)
    (java.io File)))


(defn clean []
      (doseq [^File f (file-seq (io/file "./target/classes"))]
        (.delete f)))


(defn pbcp [^String s]
      (-> (Toolkit/getDefaultToolkit)
          (.getSystemClipboard)
          (.setContents (StringSelection. s)
                        nil)))

(comment
  (clean)
  (server/start!)

  (shadow/watch :node)
  (shadow/watch :browser)
  (shadow/watch :node-test)
  (shadow/stop-worker :node-test)

  (shadow/watch-set-autobuild! :node-test false)
  (shadow/watch-set-autobuild! :browser false)
  (shadow/watch-set-autobuild! :node true)
  (shadow/watch-set-autobuild! :browser true)
  (shadow/compile :node)

  (shadow/node-repl)
  (shadow/repl :node)
  (shadow/repl :browser))
