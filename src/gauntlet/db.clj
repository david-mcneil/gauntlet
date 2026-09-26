(ns gauntlet.db
  "SQLite storage for the work queue, agent runs, verdicts and findings.
  Only the orchestrator writes here; agents have no access to the database."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql])
  (:import (java.time Instant LocalDate ZoneId ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def ^:private timestamp-format
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      (.withZone ZoneOffset/UTC)))

(defn timestamp
  "UTC timestamp with fixed-width milliseconds, so timestamps sort as strings."
  ([] (timestamp (Instant/now)))
  ([^Instant instant] (.format timestamp-format instant)))

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS work_items (
      id          INTEGER PRIMARY KEY,
      title       TEXT NOT NULL,
      spec        TEXT NOT NULL,
      status      TEXT NOT NULL,
      branch      TEXT,
      base_sha    TEXT,
      head_sha    TEXT,
      merged_sha  TEXT,
      round       INTEGER NOT NULL DEFAULT 0,
      note        TEXT,
      created_at  TEXT NOT NULL,
      updated_at  TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS events (
      id            INTEGER PRIMARY KEY,
      work_item_id  INTEGER NOT NULL REFERENCES work_items(id),
      from_status   TEXT,
      to_status     TEXT NOT NULL,
      note          TEXT,
      at            TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS runs (
      id             INTEGER PRIMARY KEY,
      work_item_id   INTEGER NOT NULL REFERENCES work_items(id),
      round          INTEGER NOT NULL,
      agent          TEXT NOT NULL,
      role           TEXT NOT NULL,
      defs_commit    TEXT NOT NULL,
      agent_version  TEXT NOT NULL,
      model          TEXT NOT NULL,
      input_sha      TEXT NOT NULL,
      output_sha     TEXT,
      status         TEXT NOT NULL,
      error          TEXT,
      cost_usd       REAL NOT NULL DEFAULT 0,
      input_tokens   INTEGER,
      output_tokens  INTEGER,
      run_dir        TEXT,
      started_at     TEXT NOT NULL,
      ended_at       TEXT)"
   "CREATE TABLE IF NOT EXISTS verdicts (
      id            INTEGER PRIMARY KEY,
      run_id        INTEGER REFERENCES runs(id),
      work_item_id  INTEGER NOT NULL REFERENCES work_items(id),
      commit_sha    TEXT NOT NULL,
      agent         TEXT NOT NULL,
      verdict       TEXT NOT NULL CHECK (verdict IN ('approve', 'reject', 'override')),
      summary       TEXT,
      created_at    TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS findings (
      id          INTEGER PRIMARY KEY,
      verdict_id  INTEGER NOT NULL REFERENCES verdicts(id),
      severity    TEXT NOT NULL CHECK (severity IN ('blocking', 'advisory')),
      file        TEXT,
      line        INTEGER,
      rule        TEXT,
      message     TEXT NOT NULL)"
   "CREATE INDEX IF NOT EXISTS runs_by_item ON runs(work_item_id)"
   "CREATE INDEX IF NOT EXISTS verdicts_by_commit ON verdicts(commit_sha, agent)"])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn open
  "Returns a datasource for the SQLite file at path, creating the schema."
  [path]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path "?foreign_keys=on")})]
    (doseq [stmt schema] (jdbc/execute! ds [stmt]))
    ds))

(defn- insert! [ds table row]
  (-> (sql/insert! ds table row {:return-keys true})
      vals first))

(defn- query [ds sql-params]
  (jdbc/execute! ds sql-params opts))

(defn- query-one [ds sql-params]
  (jdbc/execute-one! ds sql-params opts))

;; Work items

(defn add-item! [ds {:keys [title spec]}]
  (let [now (timestamp)
        id  (insert! ds :work_items {:title title :spec spec :status "queued"
                                     :created_at now :updated_at now})]
    (insert! ds :events {:work_item_id id :to_status "queued" :at now})
    id))

(defn item [ds id]
  (query-one ds ["SELECT * FROM work_items WHERE id = ?" id]))

(defn items [ds]
  (query ds ["SELECT * FROM work_items ORDER BY id"]))

(def active-statuses ["implementing" "reviewing" "queued"])

(defn next-item
  "The item to work on next: an item already in progress, otherwise the
  oldest queued item."
  [ds]
  (query-one ds ["SELECT * FROM work_items
                  WHERE status IN ('implementing', 'reviewing', 'queued')
                  ORDER BY CASE status WHEN 'queued' THEN 1 ELSE 0 END, id
                  LIMIT 1"]))

(defn update-item!
  "Updates columns on a work item. If :status is present and differs from the
  current status, records an event."
  [ds id changes]
  (jdbc/with-transaction [tx ds]
    (let [current (item tx id)
          now     (timestamp)]
      (sql/update! tx :work_items (assoc changes :updated_at now) {:id id})
      (when (and (:status changes) (not= (:status changes) (:status current)))
        (insert! tx :events {:work_item_id id :from_status (:status current)
                             :to_status (:status changes) :note (:note changes) :at now})))))

(defn events [ds item-id]
  (query ds ["SELECT * FROM events WHERE work_item_id = ? ORDER BY id" item-id]))

;; Runs

(defn start-run! [ds row]
  (insert! ds :runs (assoc row :status "running" :started_at (timestamp))))

(defn finish-run! [ds run-id changes]
  (sql/update! ds :runs (assoc changes :ended_at (timestamp)) {:id run-id}))

(defn mark-interrupted-runs!
  "Marks runs left in 'running' by an orchestrator that stopped mid-run.
  Only safe while no other orchestrator uses this database (see
  gauntlet.lock). Returns the number of runs marked."
  [ds]
  (-> (jdbc/execute-one! ds ["UPDATE runs SET status = 'interrupted', ended_at = ?,
                                error = 'orchestrator stopped during the run'
                              WHERE status = 'running'" (timestamp)])
      :next.jdbc/update-count))

(defn runs [ds item-id]
  (query ds ["SELECT * FROM runs WHERE work_item_id = ? ORDER BY id" item-id]))

(defn spent-since
  "Total cost of runs started at or after the given timestamp string."
  [ds since]
  (or (:total (query-one ds ["SELECT SUM(cost_usd) AS total FROM runs WHERE started_at >= ?" since]))
      0.0))

(defn start-of-today []
  (timestamp (.toInstant (.atStartOfDay (LocalDate/now) (ZoneId/systemDefault)))))

;; Verdicts

(defn add-verdict!
  "Records a verdict and its findings. Returns the verdict id."
  [ds {:keys [run-id item-id commit-sha agent verdict summary findings]}]
  (jdbc/with-transaction [tx ds]
    (let [vid (insert! tx :verdicts {:run_id run-id :work_item_id item-id
                                     :commit_sha commit-sha :agent agent
                                     :verdict verdict :summary summary
                                     :created_at (timestamp)})]
      (doseq [{:keys [severity file line rule message]} findings]
        (insert! tx :findings {:verdict_id vid :severity severity :file file
                               :line line :rule rule :message message}))
      vid)))

(defn verdicts [ds item-id]
  (query ds ["SELECT v.*, r.agent_version, r.model
              FROM verdicts v LEFT JOIN runs r ON r.id = v.run_id
              WHERE v.work_item_id = ? ORDER BY v.id" item-id]))

(defn findings [ds verdict-id]
  (query ds ["SELECT * FROM findings WHERE verdict_id = ? ORDER BY id" verdict-id]))

(defn prior-approval
  "An approval of this commit by this agent at this version and model, if one
  exists. A verdict depends only on those inputs, so it can be reused."
  [ds {:keys [commit-sha agent agent-version model]}]
  (query-one ds ["SELECT v.* FROM verdicts v JOIN runs r ON r.id = v.run_id
                  WHERE v.commit_sha = ? AND v.agent = ? AND v.verdict = 'approve'
                    AND r.agent_version = ? AND r.model = ?
                  ORDER BY v.id DESC LIMIT 1"
                 commit-sha agent agent-version model]))

(defn latest-rejection
  "The most recent reject verdict for an item, with its findings."
  [ds item-id]
  (when-let [v (query-one ds ["SELECT * FROM verdicts
                               WHERE work_item_id = ? AND verdict = 'reject'
                               ORDER BY id DESC LIMIT 1" item-id])]
    (assoc v :findings (findings ds (:id v)))))
