(ns gauntlet.test-support
  "Temporary git repositories and pipeline contexts for tests."
  (:require [clojure.java.io :as io]
            [gauntlet.db :as db]
            [gauntlet.git :as git]
            [gauntlet.pipeline :as pipeline]
            [gauntlet.runtime.fake :as fake])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "gauntlet-test" (make-array FileAttribute 0)))))

(defn write-file! [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)))

(defn commit-all! [dir message]
  (git/git! dir "add" "-A")
  (git/git! dir "commit" "--quiet" "-m" message)
  (git/rev-parse dir "HEAD"))

(defn init-repo!
  "Creates a git repo with the given files committed on main. Returns its path."
  [files]
  (let [dir (temp-dir)]
    (git/git! dir "init" "--quiet" "-b" "main")
    (git/git! dir "config" "user.name" "test")
    (git/git! dir "config" "user.email" "test@localhost")
    (doseq [[path content] files] (write-file! dir path content))
    (commit-all! dir "initial")
    dir))

(def registry
  "{:agents
    {:worker {:role :implement :model \"worker-model\" :max-budget-usd 1.0
              :context-paths [\"agents/worker\" \"shared/protocol.md\"]}
     :alpha  {:role :review :order 10 :model \"review-model\" :max-budget-usd 0.5
              :context-paths [\"shared/protocol.md\" \"agents/alpha\"]}
     :beta   {:role :review :order 20 :model \"review-model\" :max-budget-usd 0.5
              :inputs [:test-results]
              :context-paths [\"shared/protocol.md\" \"agents/beta\"]}}}")

(defn defs-repo! []
  (init-repo! {"agents.edn"               registry
               "shared/protocol.md"       "protocol text"
               "agents/worker/prompt.md"  "worker prompt"
               "agents/alpha/prompt.md"   "alpha prompt"
               "agents/alpha/rules.md"    "alpha rules"
               "agents/beta/prompt.md"    "beta prompt"}))

(defn project-repo! []
  (init-repo! {"README.md" "project\n"}))

(defn test-context
  "A pipeline context over fresh repos and database. Returns the context with
  the fake runtime's call log at :calls."
  ([behaviors] (test-context behaviors {}))
  ([behaviors config-overrides]
   (let [data   (temp-dir)
         config (merge {:repo             (project-repo!)
                        :main-branch      "main"
                        :defs             {:repo (defs-repo!) :ref "main"}
                        :data-dir         data
                        :max-rounds       3
                        :daily-budget-usd 100.0
                        :inputs           {:test-results {:cmd "run tests"}}
                        :runtime          {:type :fake}}
                       config-overrides)
         rt     (fake/fake-runtime behaviors)
         ctx    (pipeline/context config (db/open (str data "/gauntlet.db")) rt)]
     (assoc ctx :calls (:calls rt)))))

(defn agent-calls
  "Agent ids in the order the fake runtime ran them."
  [ctx]
  (keep :agent @(:calls ctx)))
