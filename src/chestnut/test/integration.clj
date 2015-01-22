(ns chestnut.test.integration
  (:refer-clojure :exclude [assert])
  (:import [java.lang AssertionError]
           [java.io InputStream OutputStream Writer Reader]
           [java.nio.channels Channels Selector SelectionKey]
           [java.nio ByteBuffer CharBuffer]
           [expect4j Expect4j]
           [jnr.posix POSIXFactory POSIX])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [expect4clj :refer [glob-match eof-match regex-match expect]]))

(def pids (atom []))

(defmacro assert [bool]
  `(when (not ~bool)
     (throw (AssertionError. (str "Assertion failed: " ~bool)))))

(defn process-builder [& args]
  (ProcessBuilder. args))

(defn expect4j [process-map]
  (Expect4j. (:out process-map) (:in process-map)))

(defn set-dir! [process dir]
  (.directory process (io/file dir)))

(defn make-process-map [process]
  {:process process
   :out (.getInputStream process)
   :err (.getErrorStream process)
   :in (.getOutputStream process)
   :writer (io/writer (.getOutputStream process))})

(defn start-process [pb]
  (let [process (.start pb)
        pmap (make-process-map process)]
    (swap! pids conj pmap)
    pmap))

(defn rm-rf [fname]
  (let [func (fn [func f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (io/delete-file f))]
    (func func (io/file fname))))

(defn start-lein-new []
  (rm-rf "/tmp/sesame-seed")
  (-> (process-builder "lein" "new" "chestnut" "sesame-seed" "--snapshot")
      (set-dir! "/tmp")
      (start-process)
      ((juxt (comp slurp :out) (comp slurp :err)))))

(defn start-repl []
    (-> (process-builder "lein" "repl")
        (set-dir! "/tmp/sesame-seed")
        (start-process)))

(defn do-repl-run [repl]
  (expect (expect4j repl)
       (regex-match "^.*$" [e]
                    (println (str "> " (.getMatch e))))
       (regex-match "app\\.js" [e]
                    (println "go app.js!")
                    (.write (:writer repl) "\u0004"))))

(defn generate-app []
  (let [[out err] (start-lein-new)]
    (assert (= "Generating fresh Chestnut project.\nREADME.md contains instructions to get you started.\n" out))
    (assert (= "" err))))



#_(run-tests 'chestnut.test.integration)

(comment
  (-> process-map
      (expect4j)
      (expect
       (regex-match "README" [state]
                    (println "found README!"))))

  (defn prefix-writer [writer prefix]
    (proxy [Writer] []
      (write [bs]
        (doseq [line (clojure.string/split (str bs) #"\n")]
          (.write writer prefix)
          (.write writer line)
          (.write writer "\n")))
      (flush [] (.flush writer))))

  (defn inspect-input-stream [stream]
    (proxy [InputStream] []
      (read
        ([bytes]
         (print ".")
         (let [res (.read stream bytes)]
           (println (String. bytes))
           res))
        ([bytes off len]
         (print ".")
         (let [res (.read stream bytes)]
           (println (String. bytes off len))
           res))))))

(defn posix-spawn [args env pwd]
  "Use jnr-posix to launch a subprocess

   @example
     (posix-spawn [\"ruby\" \"-e\" \"puts 'foo'\"] [\"RUBYOPT=-w\"] \"/tmp\")"
  (-> (POSIXFactory/getPOSIX)
      (.newProcessMaker (into-array String args))
      (.environment (into-array String args))
      (.directory (io/file pwd))
      (.start)))
