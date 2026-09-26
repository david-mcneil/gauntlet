(ns gauntlet.lock
  "Ensures only one orchestrator processes a queue at a time. The operating
  system releases the lock when the process exits, however it exits."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile)
           (java.nio.channels FileChannel FileLock)))

(defonce ^:private held (atom nil))

(defn acquire!
  "Locks data-dir/gauntlet.lock for the life of this process. Returns true, or
  false if another process holds it."
  [data-dir]
  (let [file    (io/file data-dir "gauntlet.lock")
        raf     (RandomAccessFile. file "rw")
        channel (.getChannel raf)
        lock    (.tryLock ^FileChannel channel)]
    (if lock
      (do (reset! held [raf lock])
          (.setLength raf 0)
          (.writeBytes raf (str (.pid (java.lang.ProcessHandle/current)) "\n"))
          true)
      (do (.close raf) false))))

(defn holder
  "The pid written by the process holding the lock, if readable."
  [data-dir]
  (let [f (io/file data-dir "gauntlet.lock")]
    (when (.exists f) (str/trim (slurp f)))))
