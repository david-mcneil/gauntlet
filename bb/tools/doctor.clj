(ns tools.doctor
  "Checks the setup in order and says what to do next.

  Sections run in order. The first section with a failing check stops the
  run, and that check's fix is printed as the next step."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [tools.conf :as conf :refer [sh ok? git]]))

;; Results

(defn- check [status msg & [fix]] {:status status :msg msg :fix fix})
(def ^:private pass (partial check :ok))
(def ^:private fail (partial check :fail))
(def ^:private warn (partial check :warn))
(def ^:private info (partial check :info))

(def ^:private labels {:ok "ok  " :fail "FAIL" :warn "warn" :info "    "})

(defn- print-check [{:keys [status msg fix]}]
  (println (str "  " (labels status) "  " msg))
  (when (and fix (not= :fail status))
    (doseq [line (str/split-lines fix)]
      (println (str "          " line)))))

(defn- money [x] (format "$%.2f" (double x)))

;; Sections. Each takes the state built so far and returns
;; {:checks [...] :state {...}}; :state is merged for later sections.

(defn- tools-section [_]
  {:checks
   (for [[cmd fix] [["git" "Install git."]
                    ["java" "Install a JDK (17 or later)."]
                    ["clojure" "Install the Clojure CLI: https://clojure.org/guides/install_clojure"]]]
     (if (fs/which cmd) (pass cmd) (fail (str cmd " not found on PATH") fix)))})

(def ^:private required-keys [:repo :defs :data-dir :max-rounds :daily-budget-usd :runtime])

(defn- config-section [_]
  (if-not (fs/exists? conf/config-file)
    {:checks [(fail (str conf/config-file " not found")
                    "Create it for your project:\n  bb init <path-to-project-repo>")]}
    (let [{:keys [config error]} (conf/read-config)
          missing (remove #(contains? config %) required-keys)]
      (cond
        error
        {:checks [(fail (str conf/config-file " is not valid EDN: " error)
                        (str "Fix the syntax in " conf/config-file "."))]}

        (seq missing)
        {:checks [(fail (str conf/config-file " is missing " (str/join ", " missing))
                        (str "Add them; " conf/example-file " shows each setting."))]}

        :else
        (let [main (or (:main-branch config) "main")]
          {:checks [(pass conf/config-file)]
           :state  {:config    config
                    :main      main
                    :repo      (conf/resolve-path (:repo config))
                    :defs-repo (conf/resolve-path (get-in config [:defs :repo]))
                    :runtime   (get-in config [:runtime :type])}})))))

(defn- first-commit-fix [repo]
  (str "Make a first commit on main:\n"
       "  git -C " repo " commit --allow-empty -m \"Initial commit\""))

(defn- project-section [{:keys [repo main config]}]
  (let [branch-ref (str "refs/heads/" main)]
    (cond
      (not (fs/directory? repo))
      {:checks [(fail (str ":repo " (pr-str (:repo config)) " resolves to " repo ", which does not exist")
                      (str "Fix :repo in " conf/config-file ", or create the repo:\n"
                           "  git init -b main " repo))]}

      (not (ok? (git repo "rev-parse" "--git-dir")))
      {:checks [(fail (str repo " is not a git repository")
                      (str "Initialize it:\n  git -C " repo " init -b main\n"
                           "then make a first commit."))]}

      (not (ok? (git repo "rev-parse" "--verify" "--quiet" (str branch-ref "^{commit}"))))
      (let [current (:out (git repo "symbolic-ref" "--short" "HEAD"))
            commits? (ok? (git repo "rev-parse" "--verify" "--quiet" "HEAD"))]
        {:checks [(fail (str "branch " main " not found in " repo)
                        (if (and commits? (seq current) (not= current main))
                          (str "The repo's branch is " current ". Either rename it:\n"
                               "  git -C " repo " branch -m " current " " main "\n"
                               "or set :main-branch " (pr-str current) " in " conf/config-file ".")
                          (first-commit-fix repo)))]})

      :else
      (let [sha        (:out (git repo "rev-parse" "--short" branch-ref))
            subject    (:out (git repo "log" "-1" "--format=%s" branch-ref))
            bare?      (= "true" (:out (git repo "rev-parse" "--is-bare-repository")))
            on-main?   (= branch-ref (:out (git repo "symbolic-ref" "--quiet" "HEAD")))
            dirty?     (and (not bare?) on-main?
                            (seq (:out (git repo "status" "--porcelain"))))
            deps-text  (git repo "show" (str branch-ref ":deps.edn"))
            deps       (when (ok? deps-text)
                         (try (edn/read-string (:out deps-text)) (catch Exception _ ::unreadable)))]
        {:checks (cond-> [(pass (str "project repo " repo))
                          (pass (str main " is at " sha " (" subject ")"))]
                   (and (not bare?) on-main?)
                   (conj (info (str main " is checked out there; merged work will appear in that working tree.")))

                   (and (not bare?) (not on-main?))
                   (conj (info (str main " is not checked out there; merges move the branch without touching the working tree.")))

                   dirty?
                   (conj (warn "the checkout has uncommitted changes"
                               "A merge fast-forwards main in that working tree and fails if\nthe changes conflict with the merged files."))

                   (= ::unreadable deps)
                   (conj (warn "deps.edn on main could not be read")))
         :state {:project-deps (when (map? deps) deps)
                 :project-has-deps? (ok? deps-text)}}))))

(defn- summary
  "Asks the orchestrator for its view of config, definitions and queue."
  []
  (let [{:keys [exit out err]} (sh "clojure" "-M:run" "summary")]
    (if (and (zero? exit) (str/starts-with? out "{"))
      (edn/read-string (last (str/split-lines out)))
      {:config-error (str "clojure -M:run summary failed: " (if (str/blank? err) out err))})))

(defn- defs-section [{:keys [defs-repo config]}]
  (let [ref (get-in config [:defs :ref])]
    (cond
      (not (ok? (git defs-repo "rev-parse" "--git-dir")))
      {:checks [(fail (str "agent definitions repo not found at " defs-repo)
                      (str "Fix :defs :repo in " conf/config-file "."))]}

      (not (ok? (git defs-repo "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))))
      {:checks [(fail (str "ref " (pr-str ref) " not found in " defs-repo)
                      (str "Fix :defs :ref in " conf/config-file ", or commit in " defs-repo "."))]}

      :else
      (let [dirty? (seq (:out (git defs-repo "status" "--porcelain")))
            s      (summary)]
        (cond
          (:config-error s)
          {:checks [(fail (str "the orchestrator could not load the config: " (:config-error s))
                          (str "Fix " conf/config-file " as described in the message."))]}

          (get-in s [:defs :error])
          {:checks [(fail (str "agent definitions are invalid: " (get-in s [:defs :error]))
                          (str "Fix agents.edn in " defs-repo " and commit."))]}

          :else
          {:checks (cond-> [(pass (str (count (get-in s [:defs :agents])) " agents loaded from "
                                       defs-repo " at " (subs (get-in s [:defs :commit]) 0 12)))]
                     dirty?
                     (conj (warn "the definitions repo has uncommitted changes"
                                 "They are not used until committed.")))
           :state {:summary s}})))))

(defn- docker-section [{:keys [config]}]
  (let [{:keys [image user env host-path-map]} (:runtime config)
        me (conf/uid-gid)]
    (cond
      (not (fs/which "docker"))
      {:checks [(fail "docker not found on PATH" "Install Docker.")]}

      (not (ok? (sh "docker" "info")))
      {:checks [(fail "cannot reach the Docker daemon"
                      "Start Docker, and make sure your user can run docker commands.")]}

      (str/blank? image)
      {:checks [(fail ":runtime has no :image" "Run: bb runtime docker")]}

      (not (ok? (sh "docker" "image" "inspect" image)))
      {:checks [(fail (str "image " image " not built") "Build it:\n  bb build-image")]}

      :else
      (let [version (sh "docker" "run" "--rm" image "claude" "--version")
            key-var "ANTHROPIC_API_KEY"]
        {:checks
         (cond-> [(pass "Docker daemon reachable")
                  (pass (str "image " image))]
           (ok? version)
           (conj (pass (str "Claude Code in image: " (:out version))))

           (not (ok? version))
           (conj (fail (str "claude does not run in " image ": " (:err version))
                       "Rebuild the image:\n  bb build-image"))

           (not (some #{key-var} env))
           (conj (fail (str ":runtime :env does not pass " key-var)
                       (str "Add \"" key-var "\" to :env, or run: bb runtime docker")))

           (and (some #{key-var} env) (str/blank? (System/getenv key-var)))
           (conj (fail (str key-var " is not set in this shell")
                       (str "Export it in the shell you run the orchestrator from:\n"
                            "  export " key-var "=...")))

           (nil? user)
           (conj (fail ":runtime has no :user"
                       (str "Containers must run as you (" me ") so they can write the run directories.\n"
                            "Run: bb runtime docker")))

           (and user (not= user me))
           (conj (fail (str ":runtime :user is " user " but you are " me)
                       (str "Set :user \"" me "\" in " conf/config-file ", or run: bb runtime docker")))

           (and (fs/exists? "/.dockerenv") (empty? host-path-map))
           (conj (warn "running inside a container with an empty :host-path-map"
                       "Bind mounts use host paths. Map this container's paths to the host's.")))}))))

(defn- runtime-section [{:keys [runtime main repo] :as state}]
  (case runtime
    :fake
    {:checks [(pass "runtime: fake (stand-in agents, no API calls)")
              (warn (str "fake runs still merge placeholder commits into " main " of the project repo")
                    (str "Use a throwaway repo, or a trial branch:\n"
                         "  git -C " repo " branch gauntlet-trial " main "\n"
                         "and set :main-branch \"gauntlet-trial\" in " conf/config-file "."))]}

    :docker
    (docker-section state)

    {:checks [(fail (str "unknown :runtime :type " (pr-str runtime))
                    "Run: bb runtime fake  or  bb runtime docker")]}))

;; Reviewer inputs

(def ^:private alias-snippets
  {"test"     ":test {:extra-paths [\"test\"]\n       :extra-deps {io.github.cognitect-labs/test-runner {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}\n       :main-opts [\"-m\" \"cognitect.test-runner\"]}"
   "coverage" ":coverage {:extra-paths [\"test\"]\n           :extra-deps {cloverage/cloverage {:mvn/version \"1.2.4\"}}\n           :main-opts [\"-m\" \"cloverage.coverage\" \"-p\" \"src\" \"-s\" \"test\"]}"})

(defn- cmd-aliases
  "Aliases named in a clojure command, e.g. \"clojure -M:test:dev\" -> [\"test\" \"dev\"]."
  [cmd]
  (when (re-find #"^\s*clojure\b|^\s*clj\b" cmd)
    (mapcat #(str/split (second %) #":")
            (re-seq #"-[MXTA]:([\w.:\-]+)" cmd))))

(defn- input-checks [{:keys [config runtime project-deps project-has-deps? repo main]} input]
  (let [cmd     (get-in config [:inputs input :cmd])
        docker? (= :docker runtime)
        problem (if docker? fail warn)]
    (if-not cmd
      [(fail (str "reviewer input " input " has no command")
             (str "Add it to :inputs in " conf/config-file ", e.g.\n  " input " {:cmd \"clojure -M:" (name input) "\"}"))]
      (let [aliases (cmd-aliases cmd)
            missing (remove #(contains? (:aliases project-deps) (keyword %)) aliases)]
        (cond
          (and (seq aliases) (not project-has-deps?))
          [(problem (str input " runs \"" cmd "\" but " repo " has no deps.edn on " main)
                    "Add a deps.edn to the project, or change the command.")]

          (seq missing)
          [(problem (str input " runs \"" cmd "\" but deps.edn has no alias "
                         (str/join ", " (map #(str ":" %) missing)))
                    (str "Add to :aliases in the project's deps.edn and commit:\n"
                         (str/join "\n" (map #(get alias-snippets % (str ":" % " {...}")) missing))
                         "\nor change the command in " conf/config-file "."))]

          :else
          [(pass (str input " -> " cmd))])))))

(defn- inputs-section [{:keys [summary runtime] :as state}]
  (let [inputs (distinct (mapcat :inputs (get-in summary [:defs :agents])))]
    {:checks (cond-> (vec (mapcat #(input-checks state %) inputs))
               (= :fake runtime)
               (conj (info "The fake runtime does not run these commands; the Docker runtime does.")))}))

(defn- budget-section [{:keys [summary config runtime]}]
  (let [agents (get-in summary [:defs :agents])
        worker (first (filter #(= :implement (:role %)) agents))
        round  (reduce + (map :max-budget-usd agents))
        daily  (:daily-budget-usd config)
        spent  (:spent summary)]
    {:checks
     (cond-> [(info (str "spent today " (money spent) " of " (money daily)
                         "; one full round costs at most " (money round)
                         (when (= :fake runtime) " (fake runs cost nothing)")))]
       (< daily (:max-budget-usd worker))
       (conj (fail (str ":daily-budget-usd " (money daily) " is below the worker's cap of "
                        (money (:max-budget-usd worker)) ", so no work can start")
                   (str "Raise :daily-budget-usd in " conf/config-file ".")))

       (and (<= (:max-budget-usd worker) daily) (< daily round))
       (conj (warn "the daily budget is less than one full round"
                   "The pipeline stops partway through reviews and resumes the next day.")))}))

(defn- queue-next-step [{:keys [summary runtime]}]
  (let [items   (:items summary)
        by      (group-by :status items)
        stuck   (first (get by "needs_human"))
        active  (first (mapcat by ["implementing" "reviewing" "queued"]))
        merged  (count (get by "merged"))]
    (cond
      stuck
      (str "Item " (:id stuck) " needs you: " (:note stuck) "\n"
           "  clojure -M:run show " (:id stuck) "\n"
           "When it is resolved, send it back through:\n"
           "  clojure -M:run requeue " (:id stuck))

      active
      (str "Process the queue (item " (:id active) " is " (:status active) "):\n"
           "  clojure -M:run run-next")

      (and (= :fake runtime) (pos? merged))
      (str merged " item(s) merged with fake agents. To run real agents:\n"
           "  bb runtime docker\n  bb doctor")

      :else
      (str "Queue a work item:\n"
           "  echo \"Describe the change\" | clojure -M:run add \"Title\" -\n"
           "then:\n  clojure -M:run run-next"))))

(defn- queue-section [{:keys [summary] :as state}]
  (let [counts (frequencies (map :status (:items summary)))]
    {:checks [(info (if (empty? counts)
                      "queue is empty"
                      (str "work items: " (str/join ", " (for [[s n] (sort counts)] (str n " " s))))))]
     :state  {:next-step (queue-next-step state)}}))

(def ^:private sections
  [["Tools"             tools-section]
   ["Config"            config-section]
   ["Project repo"      project-section]
   ["Agent definitions" defs-section]
   ["Runtime"           runtime-section]
   ["Reviewer inputs"   inputs-section]
   ["Budget"            budget-section]
   ["Queue"             queue-section]])

(defn -main [& _]
  (loop [[[title section] & more] sections
         state {}]
    (if-not section
      (do (println "\nNext step:")
          (println (str/join "\n" (map #(str "  " %) (str/split-lines (:next-step state))))))
      (let [{:keys [checks] :as result} (section state)
            failed (first (filter #(= :fail (:status %)) checks))]
        (println (str "\n" title))
        (run! print-check checks)
        (if failed
          (do (println "\nNext step:")
              (println (str/join "\n" (map #(str "  " %) (str/split-lines (:fix failed)))))
              (System/exit 1))
          (recur more (merge state (:state result))))))))
