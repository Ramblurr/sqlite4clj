(ns bench.task
  (:require
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn bench [args]
  (when-not (#{0 2} (count args))
    (throw (ex-info "Usage: bb bench [REV1 REV2]" {})))
  (let [repo (.getCanonicalPath (io/file "."))
        clojure-dep (get-in (edn/read-string (slurp "deps.edn"))
                           [:deps 'org.clojure/clojure])
        [a b :as revs] (mapv (fn [rev]
                              (str/trim (:out (shell {:out :string}
                                                "git" "rev-parse" "--verify"
                                                (str rev "^{commit}")))))
                            args)
        runs (if (seq revs)
               [["rev1-1" a] ["rev2-1" b] ["rev2-2" b] ["rev1-2" a]]
               [["worktree" nil]])
        output (io/file "bench/results" (str (System/currentTimeMillis)))
        _ (.mkdirs output)
        results
        (mapv (fn [[label revision]]
                (let [file (io/file output (str label ".edn"))
                      dep (if revision
                            {:git/url (str "file://" repo) :git/sha revision}
                            {:local/root repo})]
                  (println "Benchmark:" label (or revision repo))
                  (with-open [log (io/writer (io/file output (str label ".log")))]
                    (shell {:out log :err :out}
                      "clojure" "-Srepro" "-Sdeps"
                      (pr-str {:aliases {:bench-target
                                         {:replace-paths ["bench"]
                                          :replace-deps {'org.clojure/clojure clojure-dep
                                                         'local/sqlite4clj dep}}}})
                      "-M:bench:bench-target" label (or revision "worktree")
                      (.getAbsolutePath file)))
                  (:results (edn/read-string (slurp file)))))
              runs)]
    (println "Mean ns/op:" (if (seq revs) "REV1 / REV2 / change" "worktree"))
    (doseq [rows (apply map vector results)]
      (let [means (mapv (fn [row] (get-in row [:mean :ns])) rows)]
        (if (seq revs)
          (let [x (/ (+ (means 0) (means 3)) 2)
                y (/ (+ (means 1) (means 2)) 2)]
            (printf "%s: %.2f / %.2f / %+.1f%%%n"
              (name (:id (first rows))) x y (* 100 (dec (/ y x)))))
          (printf "%s: %.2f%n" (name (:id (first rows))) (first means)))))
    (println "Results and Criterium diagnostics:" (str output))))
