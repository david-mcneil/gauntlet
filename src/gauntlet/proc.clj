(ns gauntlet.proc
  "Runs external processes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn sh
  "Runs cmd (a sequence of strings) and returns {:exit :out :err}.
  Options: :dir working directory, :env map of extra environment variables."
  [cmd & [{:keys [dir env]}]]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))]
    (when dir (.directory pb (io/file dir)))
    (let [pb-env (.environment pb)]
      (doseq [[k v] env] (.put pb-env (str k) (str v))))
    (let [p    (.start pb)
          _    (.close (.getOutputStream p))
          out  (future (slurp (.getInputStream p)))
          err  (future (slurp (.getErrorStream p)))
          exit (.waitFor p)]
      {:exit exit :out @out :err @err})))

(defn sh!
  "Like sh, but throws on a non-zero exit and returns trimmed stdout."
  [cmd & [opts]]
  (let [{:keys [exit out err]} (sh cmd opts)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " cmd) " exited " exit ": " (str/trim err))
                      {:cmd cmd :exit exit :err err})))
    (str/trim-newline out)))
