(ns tools.queue
  "Fast versions of the queue commands (list, show, spent, add, requeue).
  They read and write the orchestrator's SQLite database through the
  go-sqlite3 pod and share their output formatting with the JVM CLI
  (gauntlet.views). The pipeline itself still runs on the JVM."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [gauntlet.views :as views]
            [pod.babashka.go-sqlite3 :as sqlite]
            [tools.conf :as conf])
  (:import (java.time Instant LocalDate ZoneId ZoneOffset)
           (java.time.format DateTimeFormatter)))

;; Same format as gauntlet.db/timestamp, so timestamps sort as strings.
(def ^:private timestamp-format
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      (.withZone ZoneOffset/UTC)))

(defn- timestamp
  ([] (timestamp (Instant/now)))
  ([instant] (.format timestamp-format instant)))

(defn- start-of-today []
  (timestamp (.toInstant (.atStartOfDay (LocalDate/now) (ZoneId/systemDefault)))))

(defn- config []
  (let [{:keys [config error]} (conf/read-config)]
    (when-not config
      (println (str "Cannot read " conf/config-file ": " (or error "not found") ". Run bb doctor."))
      (System/exit 1))
    config))

(defn- db-path []
  (let [path (str (fs/path (conf/resolve-path (:data-dir (config))) "gauntlet.db"))]
    (when-not (fs/exists? path)
      (println "No database yet at" path "- run bb doctor, or any clojure -M:run command, to create it.")
      (System/exit 1))
    path))

(defn- kebab-keys [row]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) row))

(defn- query [db sql & params]
  (map kebab-keys (sqlite/query db (into [sql] params))))

(defn- parse-id [s]
  (or (some-> s parse-long)
      (do (println "Expected an item number, got" (pr-str s)) (System/exit 1))))

(declare items)

(defn list-items [_]
  (run! println (views/list-lines (items))))

(defn show [[id]]
  (let [db   (db-path)
        id   (parse-id id)
        item (first (query db "SELECT * FROM work_items WHERE id = ?" id))]
    (if-not item
      (println "no item" id)
      (run! println
            (views/show-lines
             item
             (query db "SELECT * FROM runs WHERE work_item_id = ? ORDER BY id" id)
             (for [v (query db "SELECT v.*, r.agent_version, r.model FROM verdicts v
                                LEFT JOIN runs r ON r.id = v.run_id
                                WHERE v.work_item_id = ? ORDER BY v.id" id)]
               (assoc v :findings (query db "SELECT * FROM findings WHERE verdict_id = ? ORDER BY id"
                                         (:id v)))))))))

(defn trace [[id]]
  (let [db   (db-path)
        id   (parse-id id)
        item (first (query db "SELECT * FROM work_items WHERE id = ?" id))]
    (if-not item
      (println "no item" id)
      (run! println
            (views/trace-lines
             item
             (query db "SELECT * FROM runs WHERE work_item_id = ? ORDER BY id" id)
             (for [v (query db "SELECT * FROM verdicts WHERE work_item_id = ? ORDER BY id" id)]
               (assoc v :findings (query db "SELECT * FROM findings WHERE verdict_id = ? ORDER BY id"
                                         (:id v))))
             (query db "SELECT * FROM events WHERE work_item_id = ? ORDER BY id" id)
             (and (System/console) (nil? (System/getenv "NO_COLOR"))))))))

(defn spent [_]
  (let [total (:total (first (query (db-path) "SELECT SUM(cost_usd) AS total FROM runs WHERE started_at >= ?"
                                    (start-of-today))))]
    (println (views/spent-line (or total 0.0) (:daily-budget-usd (config))))))

(defn add-item!
  "Queues a work item and returns its id."
  [title spec]
  (let [db  (db-path)
        now (timestamp)
        id  (:last-inserted-id
             (sqlite/execute! db ["INSERT INTO work_items (title, spec, status, created_at, updated_at)
                                   VALUES (?, ?, 'queued', ?, ?)" title spec now now]))]
    (sqlite/execute! db ["INSERT INTO events (work_item_id, to_status, at) VALUES (?, 'queued', ?)" id now])
    id))

(defn items [] (query (db-path) "SELECT * FROM work_items ORDER BY id"))

(defn add [[title spec-file]]
  (if (or (str/blank? title) (nil? spec-file))
    (println "Usage: bb add <title> <spec-file>   (spec-file may be - for stdin)")
    (println "queued item" (add-item! title (if (= "-" spec-file) (slurp *in*) (slurp spec-file))))))

(defn requeue [[id]]
  (let [db   (db-path)
        id   (parse-id id)
        item (first (query db "SELECT * FROM work_items WHERE id = ?" id))
        to   (views/requeue-status (query db "SELECT * FROM events WHERE work_item_id = ? ORDER BY id" id))
        now  (timestamp)]
    (cond
      (nil? item)
      (println "no item" id)

      (not= "needs_human" (:status item))
      (println "item" id "is" (:status item) "- only needs_human items can be requeued")

      :else
      (do (sqlite/execute! db ["UPDATE work_items SET status = ?, round = 1, note = NULL, updated_at = ? WHERE id = ?"
                               to now id])
          (sqlite/execute! db ["INSERT INTO events (work_item_id, from_status, to_status, at)
                                VALUES (?, 'needs_human', ?, ?)" id to now])
          (println "item" id "->" to)))))
