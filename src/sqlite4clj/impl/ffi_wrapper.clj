(ns sqlite4clj.impl.ffi-wrapper
  (:require [babashka.ffi :as ffi]
            [clojure.java.io :as io]))

(def lookup_ (atom nil))

(defn set-library! [file-name]
  (reset! lookup_
          (when file-name
            (ffi/load-library (.getAbsolutePath (io/file file-name))))))

(defmacro defcfn [name & args]
  (let [[doc args] (if (and (string? (first args))
                            (not (vector? (second args))))
                     [[(first args)] (next args)]
                     [nil args])]
    `(ffi/defcfn ~name ~@doc {:library lookup_} ~@args)))
