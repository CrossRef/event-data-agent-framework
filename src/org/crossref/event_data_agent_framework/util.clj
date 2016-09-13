(ns org.crossref.event-data-agent-framework.util
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))


(defn text-file-to-set [file]
  "Load a newline delimited file into a set."
  (with-open [reader (io/reader file)]
    (into #{} (remove string/blank? (map #(.trim ^String %)
                                 (line-seq reader))))))

