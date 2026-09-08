(ns sqlite4clj.impl.api
  "These function map directly to SQLite's C API."
  (:require
   [babashka.ffi :as ffi]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sqlite4clj.impl.encoding :as enc]
   [sqlite4clj.impl.ffi-wrapper :as ffi-wrapper :refer [defcfn]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.lang.foreign MemorySegment]))

(set! *warn-on-reflection* true)

(def SQLITE_UTF8 1)
(def SQLITE_DETERMINISTIC 0x000000800)
(def SQLITE_DIRECTONLY 0x000080000)
(def SQLITE_INNOCUOUS 0x000200000)
(def SQLITE_SUBTYPE 0x000100000)
(def SQLITE_RESULT_SUBTYPE 0x001000000)
(def SQLITE_SELFORDER1 0x002000000)
(def SQLITE_CONFIG_MEMSTATUS 9)

(defn copy-resource [resource-path output-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (io/file output-path))]
    (io/copy in out)))

(defn get-arch+os []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (str (System/getProperty "os.arch") "-"
      (cond (str/includes? os-name "win") "windows"
            (str/includes? os-name "nux") "linux"
            (str/includes? os-name "mac") "macos"))))

(defn load-bundled-library []
  (let [res-file
        (case (get-arch+os)
          "aarch64-linux"   "sqlite3_aarch64-linux-gnu.so"
          "aarch64-macos"   "sqlite3_aarch64-macos-none.so"
          ("x86-linux"
           "x86_64-linux"
           "amd64-linux")   "sqlite3_x86_64-linux-gnu.so"
          ("x86-macos"
           "x86_64-macos"
           "amd64-macos")   "sqlite3_x86_64-macos-none.so"
          ("x86-windows"
           "x86_64-windows"
           "amd64-windows") "sqlite3_x86_64-windows-gnu.dll")
        ;; One file per process. Concurrent JVMs must never share the inode
        ;; that the dynamic linker maps.
        temp-lib (Files/createTempFile "sqlite4clj_" (str "_" res-file)
                   (make-array FileAttribute 0))]
    (try
      (copy-resource res-file (str temp-lib))
      (ffi-wrapper/set-library! (str temp-lib))
      (finally
        ;; The mapping outlives the directory entry.
        (Files/deleteIfExists temp-lib)))))

(defn load-system-library []
  (let [library-name (System/mapLibraryName "sqlite3")
        paths        (str/split (System/getProperty "java.library.path" "")
                       (re-pattern (System/getProperty "path.separator")))
        library-file (some (fn [path]
                             (let [file (io/file path library-name)]
                               (when (.isFile file) file)))
                       paths)]
    (reset! ffi-wrapper/lookup_
      (if library-file
        (ffi/load-library (.getAbsolutePath ^java.io.File library-file))
        (ffi/load-system-library "sqlite3")))))

;; Load appropriate SQLite library
(let [src (System/getProperty "sqlite4clj.native-lib")]
  (cond
    ;; default to bundled
    (or (nil? src)
      (= src "bundled")) (load-bundled-library)
    (= src "system")     (load-system-library)
    :else
    (ffi-wrapper/set-library! src)))

(defcfn initialize
  "sqlite3_initialize"
  [] :int)

(defcfn config-int
  "sqlite3_config"
  [:int :& :int] :int)

(defcfn memory-used
  "Bytes currently allocated by SQLite globally in this process.
  Returns 0 unless memstatus is enabled (see -Dsqlite4clj.memstatus=true)."
  "sqlite3_memory_used"
  [] :long)

(defcfn memory-highwater
  "High-water mark in bytes of SQLite's global allocator since process start
  (or since the last reset). Pass reset?=1 to reset the high-water mark.
  Returns 0 unless memstatus is enabled (see -Dsqlite4clj.memstatus=true)."
  "sqlite3_memory_highwater"
  [:int] :long)

;; SQLite's bundled binaries are built with SQLITE_DEFAULT_MEMSTATUS=0 so the
;; sqlite3_memory_used / sqlite3_memory_highwater counters are no-ops by
;; default (returning 0). Setting -Dsqlite4clj.memstatus=true enables them at
;; the cost of one extra atomic increment per allocation. Must be configured
;; before sqlite3_initialize, so we do it here just before init-lib runs.
(defonce init-lib
  (do
    (when (= "true" (System/getProperty "sqlite4clj.memstatus"))
      #_{:clj-kondo/ignore [:invalid-arity]}
      (config-int SQLITE_CONFIG_MEMSTATUS 1))
    (initialize)))

(defcfn free
  "sqlite3_free"
  [:pointer] :void)

(defcfn errmsg
  "sqlite3_errmsg"
  [:pointer] :string)

(defcfn errstr
  "sqlite3_errstr"
  [:int] :string)

(defn sqlite-ex-info [pdb code data]
  (let [code-name (errstr code)
        message   (errmsg pdb)]
    (ex-info (str "SQLite error: " code-name "\n" message)
      (assoc data
        :code code-name
        :message message))))

(defn sqlite-ok? [code]
  (= code 0))

(defcfn open-v2
  "sqlite3_open_v2" [:string :pointer :int :string] :int
  sqlite3-open-native
  [filename flags vfs]
  (with-open [arena (ffi/confined-arena)]
    (let [pdb           (ffi/alloc arena :pointer)
          filename-utf8 (String/new (String/.getBytes filename "UTF-8") "UTF-8")
          vfs-utf8      (when vfs
                          (String/new (String/.getBytes vfs "UTF-8") "UTF-8"))
          code          (sqlite3-open-native filename-utf8
                          pdb flags vfs-utf8)]
      (if (sqlite-ok? code)
        (ffi/read pdb :pointer)
        (throw (sqlite-ex-info pdb code {:filename filename}))))))

(defcfn close
  "sqlite3_close"
  [:pointer] :int)

(defcfn prepare-v3
  "sqlite3_prepare_v3"
  [:pointer :string :int
   :int
   :pointer :pointer] :int
  sqlite3-prepare-native
  [pdb sql]
  (with-open [arena (ffi/confined-arena)]
    (let [ppStmt (ffi/alloc arena :pointer)
          sql    (String/new (String/.getBytes sql "UTF-8") "UTF-8")
          code   (sqlite3-prepare-native pdb sql -1
                   0x01 ;; SQLITE_PREPARE_PERSISTENT
                   ppStmt
                   nil)]
      (if (sqlite-ok? code)
        (ffi/read ppStmt :pointer)
        (throw (sqlite-ex-info pdb code {:sql sql}))))))

(defcfn reset
  "sqlite3_reset"
  [:pointer] :int)

(defcfn clear-bindings
  "sqlite3_clear_bindings"
  [:pointer] :int)

(defcfn bind-int
  "sqlite3_bind_int64"
  [:pointer :int :long] :int)

(defcfn bind-double
  "sqlite3_bind_double"
  [:pointer :int :double] :int)

(defcfn bind-null
  "sqlite3_bind_null"
  [:pointer :int] :int)

(def sqlite-static (ffi/segment 0))
(def sqlite-transient (ffi/segment -1))

(defcfn bind-text
  "sqlite3_bind_text"
  [:pointer :int :string :int
   :pointer] :int
  sqlite3-bind-text-native
  [pdb idx text]
  (let [text       (str text)
        text-bytes (String/.getBytes text "UTF-8")]
    (sqlite3-bind-text-native pdb idx
      (String/new text-bytes "UTF-8")
      (count text-bytes)
      sqlite-transient)))

(defcfn bind-blob
  "sqlite3_bind_blob"
  [:pointer :int :pointer :int
   :pointer] :int
  sqlite3-bind-blob-native
  [pdb idx blob]
  (with-open [arena (ffi/confined-arena)]
    (let [segment (enc/encode arena blob)]
      (sqlite3-bind-blob-native pdb idx segment
        (MemorySegment/.byteSize segment)
        sqlite-transient))))

(defcfn step
  "sqlite3_step"
  [:pointer] :int)

(defcfn column-count
  "sqlite3_column_count"
  [:pointer] :int)

(defcfn column-double
  "sqlite3_column_double"
  [:pointer :int] :double)

(defcfn column-int
  "sqlite3_column_int64"
  [:pointer :int] :long)

(defcfn column-text
  "sqlite3_column_text"
  [:pointer :int] :string)

(defcfn column-bytes
  "sqlite3_column_bytes"
  [:pointer :int] :int)

(defcfn column-blob
  "sqlite3_column_blob"
  [:pointer :int] :pointer
  sqlite3_column_blob-native
  [stmt idx]
  (let [result (sqlite3_column_blob-native stmt idx)
        size   (column-bytes stmt idx)
        blob   (ffi/reinterpret result size)]
    (enc/decode blob size)))

(defcfn column-type
  "sqlite3_column_type"
  [:pointer :int] :int)

(defcfn finalize
  "sqlite3_finalize"
  [:pointer] :int)

(defcfn create-function-v2
  "sqlite3_create_function_v2"
  [:pointer  ;; db
   :string   ;; zFunctionName
   :int      ;; nArg
   :int      ;; eTextRep (includes flags)
   :pointer  ;; pApp (user data)
   :pointer  ;; xFunc (function pointer, not inline definition)
   :pointer  ;; xStep (for aggregates)
   :pointer  ;; xFinal (for aggregates)
   :pointer] ;; xDestroy (destructor)
  :int)

(defcfn aggregate-context
  "sqlite3_aggregate_context"
  [:pointer :int] :pointer)

(defcfn value-text
  "sqlite3_value_text"
  [:pointer] :string)

(defcfn value-int
  "sqlite3_value_int64"
  [:pointer] :long)

(defcfn value-double
  "sqlite3_value_double"
  [:pointer] :double)

(defcfn value-type
  "sqlite3_value_type"
  [:pointer] :int)

(defcfn value-bytes
  "sqlite3_value_bytes"
  [:pointer] :int)

(defcfn value-blob
  "sqlite3_value_blob"
  [:pointer] :pointer
  sqlite3-value-blob-native
  [sqlite-value]
  (let [result (sqlite3-value-blob-native sqlite-value)]
    (if (ffi/null? result)
      nil
      (let [^int size (value-bytes sqlite-value)
            blob      (ffi/reinterpret result size)]
        (enc/decode blob size)))))

(defcfn result-text
  "sqlite3_result_text"
  [:pointer :string :int :pointer] :void
  sqlite3-result-text-native
  [context text]
  (let [text-bytes (String/.getBytes (str text) "UTF-8")]
    (sqlite3-result-text-native context
      (String/new text-bytes "UTF-8")
      (count text-bytes)
      sqlite-transient)))

(defcfn result-int
  "sqlite3_result_int64"
  [:pointer :long] :void)

(defcfn result-double
  "sqlite3_result_double"
  [:pointer :double] :void)

(defcfn result-null
  "sqlite3_result_null"
  [:pointer] :void
  sqlite3-result-null-native
  ([context]
   (sqlite3-result-null-native context))
  ([context _]
   (sqlite3-result-null-native context)))

(defcfn result-blob
  "sqlite3_result_blob"
  [:pointer :pointer :int :pointer] :void
  sqlite3-result-blob-native
  [context blob]
  (with-open [arena (ffi/confined-arena)]
    (let [segment   (enc/encode arena blob)]
    (sqlite3-result-blob-native context
      segment (MemorySegment/.byteSize segment) sqlite-transient))))

(defcfn result-error
  "sqlite3_result_error"
  [:pointer :string :int] :void
  sqlite3-result-error-native
  [context msg]
  (let [msg       (str msg)
        msg-bytes (String/.getBytes msg "UTF-8")]
    (sqlite3-result-error-native context
      (String/new msg-bytes "UTF-8")
      (count msg-bytes))))

(defcfn column-database-name
  "sqlite3_column_database_name"
  [:pointer :int] :string)

(defcfn column-table-name
  "sqlite3_column_table_name"
  [:pointer :int] :string)

(defcfn column-origin-name
  "sqlite3_column_origin_name"
  [:pointer :int] :string)

(defcfn column-name
  ;; The name of a result column is the value of the "AS" clause for that
  ;; column, if there is an AS clause. If there is no AS clause then the
  ;; name of the column is unspecified and may change from one release of
  ;; SQLite to the next.
  "sqlite3_column_name"
  [:pointer :int] :string)

(defcfn sqlite3-limit
  "https://sqlite.org/c3ref/limit.html"
  "sqlite3_limit"
  [:pointer :int :int] :int)

(defcfn sqlite3-interrupt
  "https://sqlite.org/c3ref/interrupt.html"
  "sqlite3_interrupt"
  [:pointer] :void)

(defcfn sqlite3-is-interrupted
  "https://sqlite.org/c3ref/interrupt.html"
  "sqlite3_is_interrupted"
  [:pointer] :int)
