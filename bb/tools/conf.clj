(ns tools.conf
  "Reading gauntlet.edn from babashka scripts. Path handling matches
  gauntlet.config in the orchestrator."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def config-file (or (System/getenv "GAUNTLET_CONFIG") "gauntlet.edn"))

(def example-file "gauntlet.example.edn")

(def default-image "gauntlet-agent")

(defn expand-home [path]
  (if (or (= "~" path) (str/starts-with? path "~/"))
    (str (fs/home) (subs path 1))
    path))

(defn resolve-path
  "Resolves a config path against the directory holding the config file."
  [path]
  (let [path (expand-home path)]
    (str (fs/normalize (fs/absolutize (if (fs/absolute? path)
                                        path
                                        (fs/path (fs/parent (fs/absolutize config-file)) path)))))))

(defn read-config
  "Returns {:config map} or {:error message}."
  []
  (try
    {:config (edn/read-string (slurp config-file))}
    (catch Exception e
      {:error (ex-message e)})))

(defn sh
  "Runs a command and returns {:exit :out :err} without throwing."
  [& args]
  (try
    (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true}
                                                 (map str args))]
      {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
    (catch Exception e
      {:exit -1 :out "" :err (ex-message e)})))

(defn ok? [result] (zero? (:exit result)))

(defn git [dir & args]
  (apply sh "git" "-C" dir args))

(defn uid-gid []
  (str (:out (sh "id" "-u")) ":" (:out (sh "id" "-g"))))
