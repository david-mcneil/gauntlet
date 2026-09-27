(ns gauntlet.cli
  "Command line entry point.

  The config file is ./gauntlet.edn, or the path in GAUNTLET_CONFIG."
  (:require [clojure.java.io :as io]
            [gauntlet.config :as config]
            [gauntlet.db :as db]
            [gauntlet.lock :as lock]
            [gauntlet.defs :as defs]
            [gauntlet.pipeline :as pipeline]
            [gauntlet.runtime.docker :as docker]
            [gauntlet.progress :as progress]
            [gauntlet.runtime.fake :as fake]
            [gauntlet.views :as views]
            [gauntlet.watch :as watch]))

(def usage
  "Usage: clojure -M:run <command>

  add <title> <spec-file>   queue a work item; spec-file may be - for stdin
  run-next                  process the next work item
  run-all                   process items until the queue is empty or the budget stops it
  watch [poll-seconds]      keep processing, waiting for new items when the queue is empty
  list                      list work items
  show <id>                 show an item with its runs, verdicts and findings
  trace <id>                show the path an item took through the agents
  requeue <id>              return a needs_human item to the step it failed in
  agents                    show the loaded agent definitions and their versions
  spent                     show today's spend
  summary                   print setup and queue state as EDN (used by bb doctor)")

(defn- load-env []
  (let [cfg (config/load-config (or (System/getenv "GAUNTLET_CONFIG") "gauntlet.edn"))]
    (.mkdirs (io/file (:data-dir cfg)))
    {:config cfg
     :ds     (db/open (str (io/file (:data-dir cfg) "gauntlet.db")))}))

(defn- make-runtime [{:keys [runtime data-dir]}]
  (case (:type runtime)
    :fake   (fake/fake-runtime)
    :docker (docker/docker-runtime (assoc runtime :label data-dir))))

(defn- take-over-queue!
  "Before processing: make sure no other orchestrator is using this queue,
  then clean up after one that stopped mid-run."
  [{:keys [config ds]}]
  (when-not (lock/acquire! (:data-dir config))
    (println (str "Another run-next, run-all or watch (pid " (lock/holder (:data-dir config))
                  ") is already processing this queue."))
    (System/exit 1))
  (let [runs (db/mark-interrupted-runs! ds)]
    (when (pos? runs)
      (println (str (progress/stamp) " marked " runs " run(s) from an earlier stop as interrupted; resuming"))))
  (when (= :docker (get-in config [:runtime :type]))
    (let [n (docker/remove-leftovers! (assoc (:runtime config) :label (:data-dir config)))]
      (when (pos? n)
        (println (str (progress/stamp) " removed " n " leftover agent container(s)"))))))

(defn- ctx [{:keys [config ds]}]
  (assoc (pipeline/context config ds (make-runtime config))
         :on-event progress/print-event))

(defn- print-result [result]
  (println (str (progress/stamp) " item " (:item-id result) ": " (:status result)
                (case (:stopped result)
                  :budget      " (stopped: daily budget reached)"
                  :usage-limit " (stopped: usage limit reached; run again after it resets)"
                  :auth        " (stopped: the credential was rejected; run bb login, then run-next)"
                  nil)
                (when (:note result) (str " - " (:note result)))
                (when (:sha result) (str " at " (subs (:sha result) 0 12))))))


(defmulti command (fn [cmd & _] cmd))

(defmethod command "add" [_ env title spec-file]
  (let [spec (if (= "-" spec-file) (slurp *in*) (slurp spec-file))
        id   (db/add-item! (:ds env) {:title title :spec spec})]
    (println "queued item" id)))

(defmethod command "run-next" [_ env]
  (take-over-queue! env)
  (if-let [result (pipeline/run-next (ctx env))]
    (print-result result)
    (println "queue is empty")))

(defmethod command "run-all" [_ env]
  (take-over-queue! env)
  (let [c (ctx env)]
    (loop []
      (when-let [result (pipeline/run-next c)]
        (print-result result)
        (when-not (:stopped result) (recur))))))

(defmethod command "watch" [_ env & [poll-seconds]]
  (take-over-queue! env)
  (let [poll-ms (* 1000 (or (some-> poll-seconds parse-long) 30))]
    (println (str (progress/stamp) " watching the queue"))
    (when (watch/watch #(ctx env)
                       {:poll-ms       poll-ms
                        :limit-wait-ms (* 15 60 1000)
                        :report        print-result
                        :log           #(println (str (progress/stamp) " " %))})
      (System/exit 1))))

(defmethod command "list" [_ {:keys [ds]}]
  (run! println (views/list-lines (db/items ds))))

(defmethod command "show" [_ {:keys [ds]} id]
  (let [id (parse-long id)]
    (if-let [item (db/item ds id)]
      (run! println (views/show-lines item (db/runs ds id)
                                      (map #(assoc % :findings (db/findings ds (:id %)))
                                           (db/verdicts ds id))))
      (println "no item" id))))

(defn color?
  "Color output when writing to a terminal, unless NO_COLOR is set."
  []
  (and (System/console) (nil? (System/getenv "NO_COLOR"))))

(defmethod command "trace" [_ {:keys [ds]} id]
  (let [id (parse-long id)]
    (if-let [item (db/item ds id)]
      (run! println (views/trace-lines item (db/runs ds id)
                                       (map #(assoc % :findings (db/findings ds (:id %)))
                                            (db/verdicts ds id))
                                       (db/events ds id)
                                       (color?)))
      (println "no item" id))))

(defmethod command "requeue" [_ {:keys [ds]} id]
  (let [id   (parse-long id)
        item (db/item ds id)
        to   (views/requeue-status (db/events ds id))]
    (if-not (= "needs_human" (:status item))
      (println "item" id "is" (:status item) "- only needs_human items can be requeued")
      (do (db/update-item! ds id {:status to :round 1 :note nil})
          (println "item" id "->" to)))))

(defmethod command "agents" [_ {:keys [config]}]
  (let [d (defs/load-defs (get-in config [:defs :repo]) (get-in config [:defs :ref]))]
    (println "definitions commit" (:commit d))
    (doseq [a (cons (defs/worker d) (defs/reviewers d))]
      (println (format "  %-14s %-9s %-4s %-26s $%-6s v%s"
                       (name (:id a)) (name (:role a)) (or (:order a) "")
                       (:model a) (:max-budget-usd a) (views/short-sha (:version a)))))))

(defmethod command "spent" [_ {:keys [ds config]}]
  (println (views/spent-line (db/spent-since ds (db/start-of-today)) (:daily-budget-usd config))))

(defmethod command "summary" [_ {:keys [ds config]}]
  ;; Machine-readable state for bb doctor. Printed as EDN.
  (prn {:defs  (try
                 (let [d (defs/load-defs (get-in config [:defs :repo]) (get-in config [:defs :ref]))]
                   {:commit (:commit d)
                    :agents (vec (for [a (cons (defs/worker d) (defs/reviewers d))]
                                   (select-keys a [:id :role :order :model :max-budget-usd :inputs])))})
                 (catch Exception e {:error (.getMessage e)}))
        :items (mapv #(select-keys % [:id :status :title :note]) (db/items ds))
        :spent (double (db/spent-since ds (db/start-of-today)))}))

(defmethod command :default [& _]
  (println usage))

(defn -main [& [cmd & args]]
  (cond
    (or (nil? cmd) (#{"-h" "--help" "help"} cmd))
    (println usage)

    (= "summary" cmd)
    (try (command cmd (load-env))
         (catch Exception e (prn {:config-error (.getMessage e)})))

    :else
    (apply command cmd (load-env) args))
  (shutdown-agents))
