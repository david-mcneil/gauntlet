(ns gauntlet.docker-test
  (:require [clojure.test :refer [deftest is testing]]
            [gauntlet.runtime.docker :as docker]))

(def config
  {:image         "gauntlet-agent"
   :user          "1000:1000"
   :env           ["ANTHROPIC_API_KEY"]
   :host-path-map {"/workspace" "/home/me/projects"}})

(defn- request [role]
  {:role           role
   :model          "claude-haiku-4-5-20251001"
   :max-budget-usd 0.25
   :prompt         "Review."
   :system-file    "/workspace/data/runs/7/system.md"
   :dirs           {:work   "/workspace/data/runs/7/work"
                    :task   "/workspace/data/runs/7/task"
                    :inputs "/workspace/data/runs/7/inputs"
                    :out    "/workspace/data/runs/7/out"}})

(defn- mounts [cmd]
  (->> (partition 2 1 cmd)
       (filter #(= "-v" (first %)))
       (map second)
       set))

(deftest agent-command-test
  (let [cmd (docker/agent-command config (request :review))]
    (testing "paths are translated for the host daemon and the checkout is read-only for reviewers"
      (is (= #{"/home/me/projects/data/runs/7/work:/work:ro"
               "/home/me/projects/data/runs/7/task:/task:ro"
               "/home/me/projects/data/runs/7/inputs:/inputs:ro"
               "/home/me/projects/data/runs/7/out:/out:rw"
               "/home/me/projects/data/runs/7/system.md:/agent/system.md:ro"}
             (mounts cmd))))
    (testing "the API key is passed by name, not value"
      (is (some #{"ANTHROPIC_API_KEY"} cmd)))
    (testing "Claude Code runs headless with the model and budget cap"
      (is (= ["claude" "--print" "Review." "--bare" "--model" "claude-haiku-4-5-20251001"
              "--max-budget-usd" "0.25"]
             (take 8 (drop-while #(not= "claude" %) cmd))))))

  (testing "the worker's checkout is writable"
    (is (contains? (mounts (docker/agent-command config (request :implement)))
                   "/home/me/projects/data/runs/7/work:/work:rw"))))

(deftest auth-test
  (testing "API key auth uses --bare and passes ANTHROPIC_API_KEY"
    (let [cmd (docker/agent-command (dissoc config :env) (request :review))]
      (is (some #{"--bare"} cmd))
      (is (some #{"ANTHROPIC_API_KEY"} cmd))))
  (testing "subscription auth passes the OAuth token and does not use --bare"
    (let [cmd (docker/agent-command (assoc config :auth :subscription :env []) (request :review))]
      (is (not (some #{"--bare"} cmd)))
      (is (some #{"CLAUDE_CODE_OAUTH_TOKEN"} cmd))
      (is (not (some #{"ANTHROPIC_API_KEY"} cmd)))
      (is (= ["--setting-sources" "user"]
             (take 2 (drop-while #(not= "--setting-sources" %) cmd)))))))

(deftest usage-limited-test
  (is (docker/usage-limited? "Claude AI usage limit reached|1760000000"))
  (is (docker/usage-limited? "You've hit your limit · resets 3pm"))
  (is (docker/usage-limited? "API Error: 429 {\"type\":\"rate_limit_error\"}"))
  (is (not (docker/usage-limited? "Error: tool failed"))))

(deftest parse-result-test
  (is (= {:cost-usd 0.0123 :input-tokens 100 :output-tokens 20}
         (docker/parse-result "{\"total_cost_usd\":0.0123,\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}")))
  (is (= {:cost-usd 0.0} (docker/parse-result "not json"))))
