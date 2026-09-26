(ns gauntlet.progress
  "Prints pipeline progress events as timestamped lines."
  (:require [clojure.string :as str])
  (:import (java.time LocalTime)
           (java.time.format DateTimeFormatter)))

(defn stamp []
  (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss")))

(defn- short-sha [s] (if s (subs s 0 (min 7 (count s))) "-"))

(defn- duration [ms]
  (let [secs (quot (or ms 0) 1000)]
    (if (< secs 60) (str secs "s") (str (quot secs 60) "m" (format "%02d" (rem secs 60)) "s"))))

(defn- one-line [s] (str/replace (str s) #"\s*\n\s*" " "))

(defn- finding-line [{:keys [severity rule file line message]}]
  (str "      [" severity "] "
       (when rule (str rule " "))
       (when file (str file (when line (str ":" line)) " "))
       (one-line message)))

(defn event-lines
  "The lines to print for one event."
  [{:keys [type] :as e}]
  (case type
    :item-picked
    [(str "item " (:item-id e) " \"" (:title e) "\": "
          (if (= "queued" (:status e))
            "starting"
            (str "resuming (" (:status e) ", round " (:round e) ")")))]

    :round-started
    [(str "  round " (:round e)
          (when (:rejected-by e)
            (str ": rejected by " (name (:rejected-by e)) ", back to the worker")))]

    :agent-started
    [(str "  " (name (:agent e)) " (" (:model e) ") "
          (if (= :implement (:role e))
            (str "implementing from " (short-sha (:commit e)))
            (str "reviewing " (short-sha (:commit e))))
          " ...")]

    :agent-finished
    [(str "  " (name (:agent e))
          (cond
            (:stopped e)          (str " stopped (" (name (:stopped e)) ") after " (duration (:ms e)))
            (zero? (:exit e 0))   (str " finished in " (duration (:ms e))
                                       (when (pos? (or (:cost-usd e) 0))
                                         (format ", ~$%.2f" (double (:cost-usd e)))))
            :else                 (str " failed (exit " (:exit e) ") after " (duration (:ms e)))))]

    :worker-done
    [(str "  worker committed " (short-sha (:commit e)) ": " (one-line (:summary e)))]

    :input-started
    [(str "  producing " (name (:input e)) ": " (:cmd e))]

    :input-finished
    [(str "  " (name (:input e)) " done in " (duration (:ms e)) " (exit " (:exit e) ")")]

    :approval-reused
    [(str "  " (name (:agent e)) ": approve (earlier approval of " (short-sha (:commit e)) " reused)")]

    :verdict
    (cons (str "  " (name (:agent e)) ": " (str/upper-case (:verdict e))
               (when-not (str/blank? (:summary e)) (str " - " (one-line (:summary e)))))
          (map finding-line (:findings e)))

    [(str "  " (pr-str e))]))

(defn print-event [event]
  (doseq [line (event-lines event)]
    (println (str (stamp) " " line))))
