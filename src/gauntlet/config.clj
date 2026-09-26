(ns gauntlet.config
  "Loads the orchestrator configuration. Relative paths in the file are
  resolved against the directory containing it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]))

(def Config
  [:map
   [:repo :string]
   [:main-branch {:default "main"} :string]
   [:defs [:map [:repo :string] [:ref :string]]]
   [:data-dir :string]
   [:max-rounds pos-int?]
   [:daily-budget-usd [:and number? pos?]]
   [:inputs {:optional true} [:map-of :keyword [:map [:cmd :string]]]]
   [:runtime [:multi {:dispatch :type}
              [:fake [:map [:type [:= :fake]]]]
              [:docker [:map
                        [:type [:= :docker]]
                        [:image :string]
                        [:user {:optional true} :string]
                        [:env {:optional true} [:vector :string]]
                        [:host-path-map {:optional true} [:map-of :string :string]]]]]]])

(defn expand-home
  "Replaces a leading ~ with the user's home directory."
  [path]
  (if (or (= "~" path) (.startsWith ^String path "~/"))
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- absolute [base path]
  (let [f (io/file (expand-home path))]
    (.getCanonicalPath (if (.isAbsolute f) f (io/file base path)))))

(defn resolve-paths
  "Makes the path settings in config absolute, relative to base-dir."
  [config base-dir]
  (-> config
      (update :repo #(absolute base-dir %))
      (update :data-dir #(absolute base-dir %))
      (update-in [:defs :repo] #(absolute base-dir %))))

(defn load-config [path]
  (let [file   (io/file path)
        config (merge {:main-branch "main"} (edn/read-string (slurp file)))]
    (when-let [err (m/explain Config config)]
      (throw (ex-info (str "invalid config " path ": " (me/humanize err)) {})))
    (resolve-paths config (.getParentFile (.getCanonicalFile file)))))
