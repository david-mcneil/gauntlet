(ns gauntlet.views
  "Text output for queue commands. Shared by the JVM CLI and the babashka
  tasks (bb list, bb show, ...), so it uses only clojure.core and
  clojure.string. Rows use kebab-case keys."
  (:require [clojure.string :as str]))

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

;; Trace: the path an item took through the agents.

(defn- seconds-between [from to]
  (when (and from to)
    (quot (- (.toEpochMilli (java.time.Instant/parse to))
             (.toEpochMilli (java.time.Instant/parse from)))
          1000)))

(defn- duration [secs]
  (cond (nil? secs) ""
        (< secs 60) (str secs "s")
        (< secs 3600) (str (quot secs 60) "m" (format "%02d" (rem secs 60)) "s")
        :else (str (quot secs 3600) "h" (format "%02d" (quot (rem secs 3600) 60)) "m")))

(defn- sha7 [s] (if s (subs s 0 (min 7 (count s))) "-"))

(defn- cost [x] (if (and x (pos? x)) (format "~$%.2f" (double x)) ""))

(defn- painter
  "ANSI colors when color? is true, plain text otherwise."
  [color?]
  (let [codes {:green "32" :red "31" :yellow "33" :dim "2" :bold "1" :cyan "36"}]
    (fn [c s] (if color? (str "\u001b[" (codes c) "m" s "\u001b[0m") (str s)))))

(defn- run-outcome
  "Symbol, word and color for a run, given its verdict (if any)."
  [run verdict]
  (cond
    (= "interrupted" (:status run))       ["⏸" "interrupted" :yellow]
    (= "error" (:status run))             ["!" "error" :red]
    (= "running" (:status run))           ["…" "running" :cyan]
    (= "implement" (:role run))           ["●" "implemented" :cyan]
    (= "approve" (:verdict verdict))      ["✓" "approve" :green]
    (= "reject" (:verdict verdict))       ["✗" "REJECT" :red]
    :else                                 ["?" (:status run) :dim]))

(defn- timeline
  "Runs and the status events worth showing, in the order they happened."
  [runs events]
  (->> (concat (map #(assoc % :kind :run :at (:started-at %)) runs)
               (keep (fn [e]
                       (cond
                         (= "needs_human" (:to-status e))
                         (assoc e :kind :needs-human)

                         (= "needs_human" (:from-status e))
                         (assoc e :kind :requeued)

                         (= "merged" (:to-status e))
                         (assoc e :kind :merged)))
                     events))
       (sort-by (juxt :at #(if (= :run (:kind %)) 0 1)))))

(defn- passes
  "Splits the timeline into passes; each worker run starts a new one."
  [entries]
  (reduce (fn [ps e]
            (if (and (= :run (:kind e)) (= "implement" (:role e)))
              (conj ps [e])
              (if (empty? ps) [[e]] (update ps (dec (count ps)) conj e))))
          []
          entries))

(defn- rejected-back?
  "True if a pass ended with a rejection that sent the work back to the
  worker. A rejection that ran out of rounds is followed by a needs-human
  event instead, so it is not the pass's last entry."
  [pass verdict-of]
  (let [end (last pass)]
    (and (= :run (:kind end))
         (= "reject" (:verdict (verdict-of end))))))

(defn- flow-lines
  "One line per pass: the agents in order with their outcome."
  [ps verdict-of paint]
  (map-indexed
   (fn [i pass]
     (str (format "  %2d  " (inc i))
          (str/join
           " "
           (map-indexed
            (fn [j e]
              (let [arrow (if (and (pos? j) (= :run (:kind e))) "→ " "")]
                (case (:kind e)
                  :run         (let [[sym _ c] (run-outcome e (verdict-of e))]
                                 (str arrow (if (= "implement" (:role e))
                                              (paint :cyan (str (:agent e) (when (not= "ok" (:status e)) (str " " sym))))
                                              (str (:agent e) " " (paint c sym)))))
                  :needs-human (paint :yellow "⚠ needs you")
                  :requeued    (paint :yellow "↻ requeued")
                  :merged      (paint :green "⇒ merged"))))
            pass))
          (when (rejected-back? pass verdict-of) (paint :yellow "  ↺"))))
   ps))

(defn trace-lines
  "Lines showing the path an item took: which agent ran in what order, each
  verdict, and each return to the worker. verdicts carry :run-id and
  :findings."
  [item runs verdicts events color?]
  (let [paint      (painter color?)
        by-run     (into {} (map (juxt :run-id identity) verdicts))
        verdict-of #(by-run (:id %))
        entries    (timeline runs events)
        total-cost (reduce + 0.0 (keep :cost-usd runs))
        first-at   (:created-at item)
        last-at    (or (:ended-at (last runs)) (:updated-at item))
        workers    (count (filter #(= "implement" (:role %)) runs))
        ps         (passes entries)
        status-c   (case (:status item) "merged" :green "needs_human" :yellow :cyan)]
    (concat
     [(str (paint :bold (str "#" (:id item) " " (:title item))) "   "
           (paint status-c (:status item))
           (when (:merged-sha item) (str " at " (sha7 (:merged-sha item)))))
      (paint :dim (str workers " worker run(s), " (- (count runs) workers) " review(s)"
                       (when (pos? total-cost) (str ", " (cost total-cost)))
                       ", " (duration (seconds-between first-at last-at))
                       " since queued"))
      ""
      "flow (one line per pass; ↺ = sent back to the worker):"]
     (flow-lines ps verdict-of paint)
     [""]
     (mapcat
      (fn [n pass]
        (let [runs-in (filter #(= :run (:kind %)) pass)
              last-run (last runs-in)]
          (concat
           [(paint :bold (str "pass " n))]
           (mapcat
            (fn [e]
              (case (:kind e)
                :run
                (let [v            (verdict-of e)
                      [sym word c] (run-outcome e v)
                      branch       (if (= e last-run) "└" "├")
                      commits      (if (= "implement" (:role e))
                                     (str (sha7 (:input-sha e)) " → " (sha7 (:output-sha e)))
                                     (sha7 (:input-sha e)))
                      advisory     (count (filter #(= "advisory" (:severity %)) (:findings v)))]
                  (concat
                   [(format "  %s %-14s %s %s %s %7s %6s%s"
                            branch (:agent e) (paint c sym) (paint c (format "%-12s" word))
                            (paint :dim (format "%-17s" commits))
                            (duration (seconds-between (:started-at e) (:ended-at e)))
                            (cost (:cost-usd e))
                            (if (and (= "approve" (:verdict v)) (pos? advisory))
                              (paint :dim (str "  " advisory " advisory")) ""))]
                   (when (:error e)
                     [(str "  " (if (= e last-run) " " "│") "     "
                           (paint :red (:error e)))])
                   (when (:summary e)
                     [(str "  " (if (= e last-run) " " "│") "     "
                           (paint :dim (str "\"" (str/replace (:summary e) #"\s*\n\s*" " ") "\"")))])
                   (when (= "reject" (:verdict v))
                     (for [f (:findings v)]
                       (str "  " (if (= e last-run) " " "│") "   "
                            (paint (if (= "blocking" (:severity f)) :red :dim)
                                   (str/triml (finding-line f))))))))

                :needs-human
                [(paint :yellow (str "  ⚠ needs you: " (:note e)))]

                :requeued
                [(paint :yellow (str "  ↻ requeued to " (:to-status e)))]

                :merged
                [(paint :green (str "  ⇒ merged into main at " (sha7 (:merged-sha item))))]))
            pass)
           (let [v (verdict-of last-run)]
             (when (rejected-back? pass verdict-of)
               [(paint :yellow (str "  ↺ back to the worker with "
                                    (count (filter #(= "blocking" (:severity %)) (:findings v)))
                                    " blocking finding(s)"))]))
           [""])))
      (iterate inc 1)
      ps))))
