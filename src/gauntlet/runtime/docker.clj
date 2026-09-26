(ns gauntlet.runtime.docker
  "Runs each agent in a fresh container using Claude Code in headless mode.

  The container sees only the directories for its own run. Reviewers get the
  checkout read-only. --bare stops Claude Code from loading CLAUDE.md files or
  hooks from the checkout, so code under review cannot add instructions to a
  reviewer's context."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [gauntlet.proc :as proc]
            [gauntlet.runtime :as runtime]))

(defn host-path
  "Translates a path as the orchestrator sees it into the path the Docker
  daemon sees. Needed when the orchestrator itself runs in a container that
  talks to the host's daemon."
  [path-map path]
  (let [path (str path)]
    (or (some (fn [[from to]]
                (when (str/starts-with? path from)
                  (str to (subs path (count from)))))
              path-map)
        path)))

(defn- mount [path-map from to mode]
  ["-v" (str (host-path path-map from) ":" to ":" mode)])

(defn- base-args [{:keys [user env]}]
  (concat ["docker" "run" "--rm" "--init"]
          (when user ["--user" user "-e" "HOME=/tmp"])
          (mapcat (fn [var] ["-e" var]) env)))

(defn agent-command
  "The docker command line for an agent run."
  [{:keys [image host-path-map] :as config}
   {:keys [role model max-budget-usd prompt system-file dirs]}]
  (vec
   (concat
    (base-args config)
    (mount host-path-map (:work dirs) "/work" (if (= :implement role) "rw" "ro"))
    (mount host-path-map (:task dirs) "/task" "ro")
    (mount host-path-map (:inputs dirs) "/inputs" "ro")
    (mount host-path-map (:out dirs) "/out" "rw")
    (mount host-path-map system-file "/agent/system.md" "ro")
    ["-w" "/work" image
     "claude" "--print" prompt
     "--bare"
     "--model" model
     "--max-budget-usd" (str max-budget-usd)
     "--append-system-prompt-file" "/agent/system.md"
     "--permission-mode" "bypassPermissions"
     "--no-session-persistence"
     "--output-format" "json"])))

(defn command-command
  "The docker command line for producing a reviewer input."
  [{:keys [image host-path-map] :as config} {:keys [cmd work]}]
  (vec (concat (base-args config)
               (mount host-path-map work "/work" "rw")
               ["-w" "/work" image "sh" "-c" cmd])))

(defn parse-result
  "Extracts cost and token counts from Claude Code's --output-format json
  result. Returns zeros if the output is not the expected JSON."
  [out]
  (try
    (let [{:keys [total_cost_usd usage]} (json/read-str out :key-fn keyword)]
      {:cost-usd      (or total_cost_usd 0.0)
       :input-tokens  (:input_tokens usage)
       :output-tokens (:output_tokens usage)})
    (catch Exception _
      {:cost-usd 0.0})))

(defrecord DockerRuntime [config]
  runtime/AgentRuntime
  (run-agent [_ request]
    (let [{:keys [exit out err]} (proc/sh (agent-command config request))]
      (spit (:log-file request) (str out "\n--- stderr ---\n" err))
      (assoc (parse-result out) :exit exit)))
  (run-command [_ request]
    (let [{:keys [exit out err]} (proc/sh (command-command config request))]
      {:exit exit :out (str out err)})))

(defn docker-runtime [config]
  (->DockerRuntime config))
