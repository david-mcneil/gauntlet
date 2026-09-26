(ns tools.project
  "What the project repo still needs before the reviewer inputs can run, and
  the work item that asks the worker to set it up."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [tools.conf :as conf :refer [ok? git]]))

(def setup-title "Project setup")

(defn summary
  "Asks the orchestrator for its view of config, definitions and queue."
  []
  (let [{:keys [exit out err]} (conf/sh "clojure" "-M:run" "summary")]
    (if (and (zero? exit) (str/starts-with? out "{"))
      (edn/read-string (last (str/split-lines out)))
      {:config-error (str "clojure -M:run summary failed: " (if (str/blank? err) out err))})))

(defn cmd-aliases
  "Aliases named in a clojure command, e.g. \"clojure -M:test:dev\" -> [\"test\" \"dev\"]."
  [cmd]
  (when (re-find #"^\s*clojure\b|^\s*clj\b" cmd)
    (mapcat #(str/split (second %) #":")
            (re-seq #"-[MXTA]:([\w.:\-]+)" cmd))))

(defn needs
  "Compares the project at main with the reviewer input commands.
  inputs is a sequence of input keywords used by reviewers."
  [config repo inputs]
  (let [main      (str "refs/heads/" (or (:main-branch config) "main"))
        deps-text (git repo "show" (str main ":deps.edn"))
        deps      (when (ok? deps-text)
                    (try (edn/read-string (:out deps-text)) (catch Exception _ nil)))
        commands  (into (sorted-map) (for [i inputs] [i (get-in config [:inputs i :cmd])]))
        aliases   (distinct (mapcat cmd-aliases (keep val commands)))]
    {:commands        commands
     :has-deps?       (ok? deps-text)
     :missing-aliases (vec (remove #(contains? (:aliases deps) (keyword %)) aliases))
     :fake-work?      (ok? (git repo "cat-file" "-e" (str main ":FAKE-WORK.md")))}))

(defn setup-needed? [{:keys [has-deps? missing-aliases fake-work? commands]}]
  (boolean (or fake-work?
               (seq missing-aliases)
               (and (not has-deps?) (some #(seq (cmd-aliases %)) (vals commands))))))

(def ^:private alias-hints
  {"test"     "runs the tests under test/ (cognitect-labs/test-runner is a good default)"
   "coverage" "reports line coverage of src/ from the tests (cloverage is a good default)"})

(defn setup-spec
  "The work item text asking the worker to prepare the project."
  [{:keys [commands has-deps? missing-aliases fake-work?]}]
  (str/join
   "\n"
   (concat
    ["The review pipeline runs these commands on every commit, in a fresh checkout,"
     "and reviewers read their output. Make the repository ready for them:"
     ""]
    (for [[input cmd] commands] (str "- `" cmd "` (" (name input) ")"))
    ["" "Requirements:" ""]
    (when-not has-deps?
      ["- Set the repository up as a Clojure CLI project with a deps.edn."])
    (for [a missing-aliases]
      (str "- Add a `:" a "` alias to deps.edn that " (get alias-hints a "the command above needs") "."))
    (when (seq missing-aliases)
      ["- Add at least one source namespace under src/ with a function and a test for it,"
       "  unless the project already has them, so that every command above runs and passes."
       "- Add a .gitignore covering .cpcache/ and target/ if it does not already."])
    (when fake-work?
      ["- Delete FAKE-WORK.md. It was left by a trial run and is not part of the project."])
    (when (seq missing-aliases)
      [""
       "Justification for new dependencies: the test runner and coverage tool produce"
       "the test and coverage reports that the reviewers use."]))))
