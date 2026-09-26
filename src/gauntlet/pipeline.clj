(ns gauntlet.pipeline
  "Moves work items through the pipeline:

    queued -> implementing -> reviewing -> merged
                   ^              |
                   +-- rejected --+   (up to :max-rounds times)

  Any failure, a blocked worker, or running out of rounds sends the item to
  needs_human. Items stay in implementing or reviewing if the daily budget or
  a usage limit stops the pipeline, and resume from there on the next run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gauntlet.db :as db]
            [gauntlet.defs :as defs]
            [gauntlet.git :as git]
            [gauntlet.runtime :as runtime]
            [malli.core :as m]
            [malli.error :as me]))

(def Verdict
  [:map
   [:verdict [:enum "approve" "reject"]]
   [:summary :string]
   [:findings [:vector
               [:map
                [:severity [:enum "blocking" "advisory"]]
                [:file {:optional true} [:maybe :string]]
                [:line {:optional true} [:maybe int?]]
                [:rule {:optional true} [:maybe :string]]
                [:message :string]]]]])

(def WorkerResult
  [:map
   [:status [:enum "done" "blocked"]]
   [:summary :string]])

(def worker-prompt
  "Implement the work item described in /task/task.md, following your instructions. Write /out/result.json when finished.")

(def reviewer-prompt
  "Review the commit checked out at /work, following your instructions. Write /out/verdict.json when finished.")

(defn- branch-name [item] (str "gauntlet/" (:id item)))

(defn- mkdirs [& parts]
  (doto (apply io/file parts) (.mkdirs)))

(defn- read-output
  "Reads and validates a JSON file written by an agent. Returns
  {:value data} or {:error message}."
  [file schema]
  (if-not (.exists (io/file file))
    {:error (str "agent did not write " (.getName (io/file file)))}
    (try
      (let [data (json/read-str (slurp file) :key-fn keyword)]
        (if-let [err (m/explain schema data)]
          {:error (str "invalid " (.getName (io/file file)) ": " (me/humanize err))}
          {:value data}))
      (catch Exception e
        {:error (str "unreadable " (.getName (io/file file)) ": " (.getMessage e))}))))

(defn- to-needs-human [{:keys [ds]} item note]
  (db/update-item! ds (:id item) {:status "needs_human" :note note})
  {:item-id (:id item) :status "needs_human" :note note})

(defn- over-budget?
  "True if running an agent with this cap could take today's spend past the
  daily budget."
  [{:keys [ds config]} agent]
  (> (+ (db/spent-since ds (db/start-of-today)) (:max-budget-usd agent))
     (:daily-budget-usd config)))

(defn- run-dirs
  "Creates the directories for one run under the data directory."
  [{:keys [config]} run-id]
  (let [root (mkdirs (:data-dir config) "runs" (str run-id))]
    {:root   root
     :dirs   {:work   (io/file root "work")
              :task   (mkdirs root "task")
              :inputs (mkdirs root "inputs")
              :out    (mkdirs root "out")}}))

(defn- write-task! [task-dir item feedback]
  (spit (io/file task-dir "task.md") (str "# " (:title item) "\n\n" (:spec item) "\n"))
  (spit (io/file task-dir "base") (str (:base-sha item) "\n"))
  (when feedback
    (spit (io/file task-dir "feedback.json") (json/write-str feedback))))

(defn- feedback-for
  "Blocking findings from the latest rejection, for the worker's next round."
  [ds item]
  (when-let [v (db/latest-rejection ds (:id item))]
    {:reviewer   (:agent v)
     :commit     (:commit-sha v)
     :summary    (:summary v)
     :findings   (->> (:findings v)
                      (filter #(= "blocking" (:severity %)))
                      (mapv #(select-keys % [:file :line :rule :message])))}))

(defn- execute!
  "Starts a run record, runs the agent and records usage. Returns
  [run-id dirs result]."
  [{:keys [ds rt defs] :as ctx} item agent input-sha prompt prepare!]
  (let [run-id (db/start-run! ds {:work_item_id (:id item) :round (:round item)
                                  :agent (name (:id agent)) :role (name (:role agent))
                                  :defs_commit (:commit defs) :agent_version (:version agent)
                                  :model (:model agent) :input_sha input-sha})
        {:keys [root dirs]} (run-dirs ctx run-id)
        system-file (io/file root "system.md")]
    (spit system-file (:context agent))
    (prepare! dirs)
    (let [result (runtime/run-agent rt {:agent          (:id agent)
                                        :role           (:role agent)
                                        :model          (:model agent)
                                        :max-budget-usd (:max-budget-usd agent)
                                        :prompt         prompt
                                        :system-file    system-file
                                        :dirs           dirs
                                        :log-file       (io/file root "agent.log")})]
      (db/finish-run! ds run-id {:cost_usd      (or (:cost-usd result) 0.0)
                                 :input_tokens  (:input-tokens result)
                                 :output_tokens (:output-tokens result)
                                 :run_dir       (str root)
                                 :status        "running"})
      [run-id dirs result])))

(defn- fail-run! [ds run-id error]
  (db/finish-run! ds run-id {:status "error" :error error})
  error)

;; Worker

(defn- implement!
  "Runs the worker. Returns the updated item, or a needs_human result."
  [{:keys [ds config defs] :as ctx} item]
  (let [agent     (defs/worker defs)
        repo      (:repo config)
        start-sha (or (:head-sha item) (:base-sha item))
        feedback  (feedback-for ds item)
        [run-id dirs result]
        (execute! ctx item agent start-sha worker-prompt
                  (fn [dirs]
                    (git/clone-at! repo (:work dirs) start-sha "work")
                    (write-task! (:task dirs) item feedback)))]
    (cond
      (:limited result)
      (do (fail-run! ds run-id "usage limit")
          {:item-id (:id item) :status (:status item) :stopped :usage-limit})

      (not (zero? (:exit result)))
      (to-needs-human ctx item (fail-run! ds run-id (str "worker exited " (:exit result))))

      :else
      (let [{:keys [value error]} (read-output (io/file (:out dirs) "result.json") WorkerResult)]
        (cond
          error
          (to-needs-human ctx item (fail-run! ds run-id error))

          (= "blocked" (:status value))
          (do (db/finish-run! ds run-id {:status "ok"})
              (to-needs-human ctx item (str "worker blocked: " (:summary value))))

          :else
          (let [head (git/fetch-head! repo (:work dirs) (branch-name item))]
            (cond
              (= head start-sha)
              (to-needs-human ctx item (fail-run! ds run-id "worker made no commits"))

              (not (git/ancestor? repo (:base-sha item) head))
              (to-needs-human ctx item (fail-run! ds run-id "worker rewrote history below the base commit"))

              :else
              (do (db/finish-run! ds run-id {:status "ok" :output_sha head})
                  (db/update-item! ds (:id item) {:status "reviewing" :head_sha head :note nil})
                  (db/item ds (:id item))))))))))

;; Reviewers

(defn- produce-inputs!
  "Writes each input the reviewer needs into inputs-dir. Inputs are computed
  once per commit and reused by later reviewers."
  [{:keys [rt config]} item agent inputs-dir]
  (doseq [input (:inputs agent)]
    (let [cache (io/file (:data-dir config) "inputs" (:head-sha item) (name input))]
      (when-not (.exists cache)
        (let [cmd  (or (get-in config [:inputs input :cmd])
                       (throw (ex-info (str "no command configured for input " input) {})))
              work (io/file (:data-dir config) "inputs" (:head-sha item) (str (name input) "-work"))]
          (git/clone-at! (:repo config) work (:head-sha item) "review")
          (let [{:keys [exit out]} (runtime/run-command rt {:input input :cmd cmd :work work})]
            (spit cache (str "exit " exit "\n\n" out)))))
      (io/copy cache (io/file inputs-dir (name input))))))

(defn- review!
  "Runs one reviewer on the item's head commit. Returns :approve, :reject, or
  {:error message}."
  [{:keys [ds config] :as ctx} item agent]
  (let [head (:head-sha item)]
    (if (db/prior-approval ds {:commit-sha head :agent (name (:id agent))
                               :agent-version (:version agent) :model (:model agent)})
      :approve
      (let [[run-id dirs result]
            (execute! ctx item agent head reviewer-prompt
                      (fn [dirs]
                        (git/clone-at! (:repo config) (:work dirs) head "review")
                        (write-task! (:task dirs) item nil)
                        (produce-inputs! ctx item agent (:inputs dirs))))]
        (cond
          (:limited result)
          (do (fail-run! ds run-id "usage limit")
              {:stopped :usage-limit})

          (not (zero? (:exit result)))
          {:error (fail-run! ds run-id (str (name (:id agent)) " exited " (:exit result)))}

          :else
          (let [{:keys [value error]} (read-output (io/file (:out dirs) "verdict.json") Verdict)
                blocking? (some #(= "blocking" (:severity %)) (:findings value))]
            (cond
              error
              {:error (fail-run! ds run-id (str (name (:id agent)) ": " error))}

              (not= (boolean blocking?) (= "reject" (:verdict value)))
              {:error (fail-run! ds run-id (str (name (:id agent))
                                                ": verdict must be reject exactly when there is a blocking finding"))}

              :else
              (do (db/finish-run! ds run-id {:status "ok"})
                  (db/add-verdict! ds {:run-id run-id :item-id (:id item) :commit-sha head
                                       :agent (name (:id agent)) :verdict (:verdict value)
                                       :summary (:summary value) :findings (:findings value)})
                  (keyword (:verdict value))))))))))

;; Merge

(defn- merge! [{:keys [ds config] :as ctx} item]
  (let [repo (:repo config)
        main (:main-branch config)]
    (if (not= (:base-sha item) (git/rev-parse repo main))
      (to-needs-human ctx item (str main " moved since the work started; rebase is not automated yet"))
      (do (git/fast-forward! repo main (:base-sha item) (:head-sha item))
          (db/update-item! ds (:id item) {:status "merged" :merged_sha (:head-sha item) :note nil})
          {:item-id (:id item) :status "merged" :sha (:head-sha item)}))))

;; Driver

(defn- start! [{:keys [ds config]} item]
  (let [base (or (git/rev-parse (:repo config) (:main-branch config))
                 (throw (ex-info (str "branch not found: " (:main-branch config)) {})))]
    (db/update-item! ds (:id item) {:status "implementing" :branch (branch-name item)
                                    :base_sha base :round 1})
    (db/item ds (:id item))))

(defn- advance
  "Takes one step for an item. Returns the updated item to continue with, or a
  result map when the item leaves the pipeline or the pipeline stops."
  [{:keys [ds config defs] :as ctx} item]
  (case (:status item)
    "queued"
    (start! ctx item)

    "implementing"
    (if (over-budget? ctx (defs/worker defs))
      {:item-id (:id item) :status "implementing" :stopped :budget}
      (implement! ctx item))

    "reviewing"
    (loop [[agent & more] (defs/reviewers defs)]
      (cond
        (nil? agent)
        (merge! ctx item)

        (over-budget? ctx agent)
        {:item-id (:id item) :status "reviewing" :stopped :budget}

        :else
        (let [outcome (review! ctx item agent)]
          (cond
            (= :approve outcome) (recur more)
            (:stopped outcome)   {:item-id (:id item) :status "reviewing" :stopped (:stopped outcome)}
            (:error outcome)     (to-needs-human ctx item (:error outcome))
            (>= (:round item) (:max-rounds config))
            (to-needs-human ctx item (str "rejected by " (name (:id agent))
                                          " after " (:round item) " rounds"))
            :else
            (do (db/update-item! ds (:id item) {:status "implementing" :round (inc (:round item))})
                (db/item ds (:id item)))))))))

(defn process-item
  "Runs an item until it is merged, needs a human, or the budget stops it."
  [ctx item]
  (loop [item item]
    (let [step (advance ctx item)]
      (if (contains? step :item-id)
        step
        (recur step)))))

(defn run-next
  "Processes the next item in the queue. Returns a result map, or nil if the
  queue is empty."
  [{:keys [ds] :as ctx}]
  (when-let [item (db/next-item ds)]
    (try
      (process-item ctx item)
      (catch Exception e
        (to-needs-human ctx (db/item ds (:id item)) (str "orchestrator error: " (.getMessage e)))))))

(defn context
  "Builds the pipeline context. config must have absolute paths."
  [config ds rt]
  {:config config
   :ds     ds
   :rt     rt
   :defs   (defs/load-defs (get-in config [:defs :repo]) (get-in config [:defs :ref]))})
