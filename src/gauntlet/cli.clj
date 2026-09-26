(ns gauntlet.cli
  "Command line entry point.

  The config file is ./gauntlet.edn, or the path in GAUNTLET_CONFIG."
  (:require [clojure.java.io :as io]
            [gauntlet.config :as config]
            [gauntlet.db :as db]
            [gauntlet.defs :as defs]
            [gauntlet.pipeline :as pipeline]
            [gauntlet.runtime.docker :as docker]
            [gauntlet.runtime.fake :as fake]))

(def usage
  "Usage: clojure -M:run <command>

  add <title> <spec-file>   queue a work item; spec-file may be - for stdin
  run-next                  process the next work item
  run-all                   process items until the queue is empty or the budget stops it
  list                      list work items
  show <id>                 show an item with its runs, verdicts and findings
  requeue <id>              return a needs_human item to the step it failed in
  agents                    show the loaded agent definitions and their versions
  spent                     show today's spend
  summary                   print setup and queue state as EDN (used by bb doctor)")

(defn- load-env []
  (let [cfg (config/load-config (or (System/getenv "GAUNTLET_CONFIG") "gauntlet.edn"))]
    (.mkdirs (io/file (:data-dir cfg)))
    {:config cfg
     :ds     (db/open (str (io/file (:data-dir cfg) "gauntlet.db")))}))

(defn- make-runtime [{:keys [runtime]}]
  (case (:type runtime)
    :fake   (fake/fake-runtime)
    :docker (docker/docker-runtime runtime)))

(defn- ctx [{:keys [config ds]}]
  (pipeline/context config ds (make-runtime config)))

(defn- print-result [result]
  (println (str "item " (:item-id result) ": " (:status result)
                (case (:stopped result)
                  :budget      " (stopped: daily budget reached)"
                  :usage-limit " (stopped: usage limit reached; run again after it resets)"
                  :auth        " (stopped: the credential was rejected; run bb login, then run-next)"
                  nil)
                (when (:note result) (str " - " (:note result)))
                (when (:sha result) (str " at " (subs (:sha result) 0 12))))))

(defn- short-sha [s] (if s (subs s 0 (min 12 (count s))) "-"))

(defmulti command (fn [cmd & _] cmd))

(defmethod command "add" [_ env title spec-file]
  (let [spec (if (= "-" spec-file) (slurp *in*) (slurp spec-file))
        id   (db/add-item! (:ds env) {:title title :spec spec})]
    (println "queued item" id)))

(defmethod command "run-next" [_ env]
  (if-let [result (pipeline/run-next (ctx env))]
    (print-result result)
    (println "queue is empty")))

(defmethod command "run-all" [_ env]
  (let [c (ctx env)]
    (loop []
      (when-let [result (pipeline/run-next c)]
        (print-result result)
        (when-not (:stopped result) (recur))))))

(defmethod command "list" [_ {:keys [ds]}]
  (doseq [{:keys [id status round head-sha title note]} (db/items ds)]
    (println (format "%4d  %-12s round %d  %-12s  %s%s"
                     id status round (short-sha head-sha) title
                     (if note (str "  (" note ")") "")))))

(defmethod command "show" [_ {:keys [ds]} id]
  (let [id   (parse-long id)
        item (db/item ds id)]
    (println (str "#" id " " (:title item)))
    (println "status:" (:status item) " round:" (:round item) " branch:" (:branch item))
    (println "base:" (short-sha (:base-sha item)) " head:" (short-sha (:head-sha item))
             " merged:" (short-sha (:merged-sha item)))
    (when (:note item) (println "note:" (:note item)))
    (println "\nruns:")
    (doseq [r (db/runs ds id)]
      (println (format "  %4d  round %d  %-14s %-8s %-26s v%s  %s %-15s $%.4f%s"
                       (:id r) (:round r) (:agent r) (:status r) (:model r)
                       (short-sha (:agent-version r)) (short-sha (:input-sha r))
                       (if (:output-sha r) (str "-> " (short-sha (:output-sha r))) "") (double (:cost-usd r))
                       (if (:error r) (str "  " (:error r)) ""))))
    (println "\nverdicts:")
    (doseq [v (db/verdicts ds id)]
      (println (format "  %-14s %-8s %s  %s" (:agent v) (:verdict v)
                       (short-sha (:commit-sha v)) (or (:summary v) "")))
      (doseq [f (db/findings ds (:id v))]
        (println (str "      [" (:severity f) "] "
                      (when (:rule f) (str (:rule f) " "))
                      (when (:file f) (str (:file f) (when (:line f) (str ":" (:line f))) " "))
                      (:message f)))))))

(defmethod command "requeue" [_ {:keys [ds]} id]
  (let [id     (parse-long id)
        item   (db/item ds id)
        before (->> (db/events ds id)
                    (filter #(= "needs_human" (:to-status %)))
                    last
                    :from-status)]
    (if-not (= "needs_human" (:status item))
      (println "item" id "is" (:status item) "- only needs_human items can be requeued")
      (do (db/update-item! ds id {:status (or before "queued") :round 1 :note nil})
          (println "item" id "->" (or before "queued"))))))

(defmethod command "agents" [_ {:keys [config]}]
  (let [d (defs/load-defs (get-in config [:defs :repo]) (get-in config [:defs :ref]))]
    (println "definitions commit" (:commit d))
    (doseq [a (cons (defs/worker d) (defs/reviewers d))]
      (println (format "  %-14s %-9s %-4s %-26s $%-6s v%s"
                       (name (:id a)) (name (:role a)) (or (:order a) "")
                       (:model a) (:max-budget-usd a) (short-sha (:version a)))))))

(defmethod command "spent" [_ {:keys [ds config]}]
  (println (format "today: $%.4f of $%.2f"
                   (double (db/spent-since ds (db/start-of-today)))
                   (double (:daily-budget-usd config)))))

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
