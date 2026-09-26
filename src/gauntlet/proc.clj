(ns gauntlet.proc
  "Runs external processes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private live-processes (atom #{}))

(defonce ^:private shutting-down (atom false))

(defonce ^:private stop-children-on-exit
  ;; On Ctrl-C or kill, stop child processes (docker run) so that no agent
  ;; keeps running after the orchestrator has gone. docker run passes the
  ;; signal on to the container.
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (reset! shutting-down true)
                               (doseq [^Process p @live-processes]
                                 (.destroy p))
                               (doseq [^Process p @live-processes]
                                 (.waitFor p 10 java.util.concurrent.TimeUnit/SECONDS))))))

(defn sh
  "Runs cmd (a sequence of strings) and returns {:exit :out :err}.
  Options: :dir working directory, :env map of extra environment variables."
  [cmd & [{:keys [dir env]}]]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))]
    (when dir (.directory pb (io/file dir)))
    (let [pb-env (.environment pb)]
      (doseq [[k v] env] (.put pb-env (str k) (str v))))
    (let [p (.start pb)]
      (swap! live-processes conj p)
      (try
        (.close (.getOutputStream p))
        (let [out  (future (slurp (.getInputStream p)))
              err  (future (slurp (.getErrorStream p)))
              exit (.waitFor p)]
          (when @shutting-down
            ;; The process was stopped by the shutdown hook. Do not return:
            ;; the caller would record the stop as an agent failure. The run
            ;; stays 'running' and the next start marks it interrupted.
            @(promise))
          {:exit exit :out @out :err @err})
        (finally
          (swap! live-processes disj p))))))

(defn sh!
  "Like sh, but throws on a non-zero exit and returns trimmed stdout."
  [cmd & [opts]]
  (let [{:keys [exit out err]} (sh cmd opts)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " cmd) " exited " exit ": " (str/trim err))
                      {:cmd cmd :exit exit :err err})))
    (str/trim-newline out)))
