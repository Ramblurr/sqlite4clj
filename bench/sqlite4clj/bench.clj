(ns sqlite4clj.bench
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [criterium.array :as array]
   [criterium.bench :as bench]
   [sqlite4clj.core :as d]
   [sqlite4clj.impl.api :as api])
  (:import
   [java.lang.management ManagementFactory]
   [java.time Instant]))

(def collection-plan
  (merge (:collect-plan (bench/options->bench-plan {}))
         {:batch-time-ns 1000000
          :num-warmup-samples 10000
          :num-measure-samples 200
          :limit-time-ns 15000000000}))

(defn result-summary [id]
  (let [data (:data (bench/last-bench))
        samples (:samples data)
        batch-size (:batch-size samples)
        timing (get-in data [:bootstrap-stats :bootstrap :elapsed-time])
        estimate (fn [entry]
                   {:ns (/ (:point-estimate entry) batch-size)
                    :ci-95-ns (mapv #(/ (:value %) batch-size)
                                    (or (:adjusted-estimate-quantiles entry)
                                        (:estimate-quantiles entry)))})]
    {:id id
     :batch-size batch-size
     :sample-count (:num-samples samples)
     :mean (estimate (:mean timing))
     :median (estimate (get-in timing [:quantiles 0.5]))
     :raw-sample-ns (mapv #(/ % batch-size)
                          (array/fold (get-in samples [:metric->values [:elapsed-time]])
                                      conj []))}))

(defn workloads [writer stmt]
  [[:native-column-count #(api/column-count stmt) #(= 1 %)]
   [:cached-scalar #(d/q writer ["SELECT ?, ?, ?" 42 1.25 nil])
    #(= [[42 1.25 nil]] %)]
   [:text-point-lookup #(d/q writer ["SELECT name FROM items WHERE id = ?" 42])
    #(= ["user-42-世界"] %)]
   [:primitive-scan-100 #(d/q writer ["SELECT id, name, score FROM items ORDER BY id"])
    #(and (= 100 (count %)) (= [0 "user-0-世界" 0.5] (first %)))]
   [:edn-scan-100 #(d/q writer ["SELECT data FROM items ORDER BY id"])
    #(and (= 100 (count %)) (= {:id 0 :tags [:a :b] :active true} (first %)))]
   [:blob-scan-100 #(d/q writer ["SELECT raw FROM items ORDER BY id"])
    #(and (= 100 (count %)) (= (vec (range 64)) (vec (first %))))]
   [:callback-scan-100 #(d/q writer ["SELECT clj_inc(id) FROM items ORDER BY id"])
    #(= (vec (range 1 101)) %)]
   [:cached-update #(d/q writer ["UPDATE items SET score = ? WHERE id = ?" 42.5 42])
    empty?]])

(defn run-benchmarks [label revision output]
  (let [db (d/init-db! ":memory:" {:pool-size 1})
        writer (:writer db)
        results (atom [])
        environment {:label label
                     :revision revision
                     :started-at (str (Instant/now))
                     :java-version (System/getProperty "java.runtime.version")
                     :clojure-version (clojure-version)
                     :os (System/getProperty "os.name")
                     :arch (System/getProperty "os.arch")
                     :jvm-args (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
                     :source (str (io/resource "sqlite4clj/impl/api.clj"))
                     :collection-plan collection-plan}]
    (try
      (d/q writer ["CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB, raw BLOB)"])
      (d/with-write-tx [tx writer]
        (doseq [id (range 100)]
          (d/q tx ["INSERT INTO items VALUES (?, ?, ?, ?, ?)"
                   id (str "user-" id "-世界") (+ id 0.5)
                   {:id id :tags [:a :b] :active true} (byte-array (range 64))])))
      (d/create-function db "clj_inc" inc {:arity 1})
      (d/with-conn [conn writer]
        (let [stmt (api/prepare-v3 (:pdb conn) "SELECT 1")]
          (try
            (doseq [[id f check] (workloads conn stmt)]
              (when-not (check (f))
                (throw (ex-info "Benchmark correctness check failed" {:id id}))))
            (doseq [[id f _] (workloads conn stmt)]
              (println "Benchmark:" label id)
              (flush)
              (bench/bench (f) :collect-plan collection-plan)
              (swap! results conj (result-summary id))
              (spit output (with-out-str
                             (pprint/pprint (assoc environment :results @results)))))
            (finally
              (api/finalize stmt)))))
      (finally
        ((:close (:writer db)))
        ((:close (:reader db)))))))

(defn -main [label revision output]
  (run-benchmarks label revision output)
  (shutdown-agents))
