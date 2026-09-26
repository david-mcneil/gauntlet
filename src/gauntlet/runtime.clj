(ns gauntlet.runtime
  "The boundary between the orchestrator and whatever executes agents.")

(defprotocol AgentRuntime
  (run-agent [rt request]
    "Runs one agent to completion. request keys:
      :agent           agent id keyword
      :role            :implement or :review
      :model           model ID
      :max-budget-usd  spending cap for the run
      :prompt          the user message that starts the run
      :system-file     file holding the assembled agent context
      :dirs            {:work :task :inputs :out} host directories
      :log-file        file to write the runtime's raw output to
    Returns {:exit int :cost-usd number :input-tokens int :output-tokens int},
    plus :stopped :usage-limit or :auth when a limit or a rejected credential
    stopped the run. Those stop the pipeline without failing the item.
    The agent's actual output is whatever it wrote to (:out dirs).")
  (run-command [rt request]
    "Runs a shell command against a checkout to produce a reviewer input
    (test results, coverage). request keys: :input (keyword), :cmd (string),
    :work (checkout directory, writable). Returns {:exit int :out string}."))
