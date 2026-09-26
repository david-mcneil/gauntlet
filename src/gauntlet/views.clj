(ns gauntlet.views
  "Text output for queue commands. Shared by the JVM CLI and the babashka
  tasks (bb list, bb show, ...), so it uses only clojure.core and
  clojure.string. Rows use kebab-case keys.")

(defn short-sha [s] (if s (subs s 0 (min 12 (count s))) "-"))

(defn list-lines [items]
  (if (empty? items)
    ["queue is empty"]
    (for [{:keys [id status round head-sha title note]} items]
      (format "%4d  %-12s round %d  %-12s  %s%s"
              id status round (short-sha head-sha) title
              (if note (str "  (" note ")") "")))))

(defn finding-line [{:keys [severity rule file line message]}]
  (str "      [" severity "] "
       (when rule (str rule " "))
       (when file (str file (when line (str ":" line)) " "))
       message))

(defn show-lines
  "item, its runs, and its verdicts each with :findings."
  [item runs verdicts]
  (concat
   [(str "#" (:id item) " " (:title item))
    (str "status: " (:status item) "  round: " (:round item) "  branch: " (:branch item))
    (str "base: " (short-sha (:base-sha item)) "  head: " (short-sha (:head-sha item))
         "  merged: " (short-sha (:merged-sha item)))]
   (when (:note item) [(str "note: " (:note item))])
   ["" "runs:"]
   (for [r runs]
     (format "  %4d  round %d  %-14s %-8s %-26s v%s  %s %-15s $%.4f%s"
             (:id r) (:round r) (:agent r) (:status r) (:model r)
             (short-sha (:agent-version r)) (short-sha (:input-sha r))
             (if (:output-sha r) (str "-> " (short-sha (:output-sha r))) "")
             (double (or (:cost-usd r) 0))
             (if (:error r) (str "  " (:error r)) "")))
   ["" "verdicts:"]
   (mapcat (fn [v]
             (cons (format "  %-14s %-8s %s  %s" (:agent v) (:verdict v)
                           (short-sha (:commit-sha v)) (or (:summary v) ""))
                   (map finding-line (:findings v))))
           verdicts)))

(defn spent-line [spent daily-budget]
  (format "today: $%.4f of $%.2f" (double spent) (double daily-budget)))

(defn requeue-status
  "The status a needs_human item returns to: the one it had before, from its
  events."
  [events]
  (or (->> events (filter #(= "needs_human" (:to-status %))) last :from-status)
      "queued"))
