(ns user
  (:import
    (java.awt Toolkit)
    (java.awt.datatransfer StringSelection)))


(defn pbcp [^String s]
      (-> (Toolkit/getDefaultToolkit)
          (.getSystemClipboard)
          (.setContents (StringSelection. s)
                        nil)))
