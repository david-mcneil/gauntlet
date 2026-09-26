(ns gauntlet.watch
  "Keeps processing the queue, waiting for new work when it is empty."
  (:require [gauntlet.pipeline :as pipeline])
  (:import (java.time Duration LocalDate LocalDateTime ZoneId)))

(defn ms-until-tomorrow
  "Milliseconds until one minute past the next local midnight, when the daily
  budget starts over."
  []
  (let [zone     (ZoneId/systemDefault)
        now      (LocalDateTime/now zone)
        tomorrow (.plusMinutes (.atStartOfDay (.plusDays (LocalDate/now zone) 1)) 1)]
    (.toMillis (Duration/between now tomorrow))))

(defn next-action
  "What to do after a run-next result: {:continue true}, {:sleep ms :reason r}
  or {:exit message}."
  [result {:keys [poll-ms limit-wait-ms]}]
  (cond
    (:error result)                    {:sleep poll-ms :reason :error}
    (nil? result)                      {:sleep poll-ms :reason :idle}
    (= :budget (:stopped result))      {:sleep (ms-until-tomorrow) :reason :budget}
    (= :usage-limit (:stopped result)) {:sleep limit-wait-ms :reason :usage-limit}
    (= :auth (:stopped result))        {:exit "The credential was rejected. Run bb login, then start watch again."}
    :else                              {:continue true}))

(defn- minutes [ms] (format "%.0f min" (/ ms 60000.0)))

(defn watch
  "Runs items until stopped. make-ctx builds a fresh pipeline context each
  time, so committed changes to the agent definitions are picked up between
  items. Options:
    :poll-ms        wait between checks of an empty queue
    :limit-wait-ms  wait after a usage limit
    :report         (fn [result]) called for each item result
    :log            (fn [message]) for waiting and error messages
    :sleep!         (fn [ms]), Thread/sleep by default
    :max-idle       stop after this many consecutive empty checks (for tests)
  Returns the exit message when it stops on its own."
  [make-ctx {:keys [log report sleep! max-idle]
             :or   {sleep! #(Thread/sleep (long %))}
             :as   opts}]
  (loop [idle 0]
    (let [result (try
                   (pipeline/run-next (make-ctx))
                   (catch Exception e {:error (.getMessage e)}))
          {:keys [continue sleep reason exit]} (next-action result opts)]
      (when (and result (not (:error result))) (report result))
      (cond
        exit
        (do (log exit) exit)

        continue
        (recur 0)

        (and (= :idle reason) max-idle (>= (inc idle) max-idle))
        nil

        :else
        (do (case reason
              :idle        (when (zero? idle)
                             (log (str "Queue is empty; waiting for work (checking every "
                                       (/ (:poll-ms opts) 1000) " s). Ctrl-C to stop.")))
              :error       (log (str "Error: " (:error result) ". Retrying in "
                                     (/ (:poll-ms opts) 1000) " s."))
              :budget      (log (str "Daily budget reached. Resuming after midnight (in "
                                     (minutes sleep) ")."))
              :usage-limit (log (str "Usage limit reached. Retrying in " (minutes sleep) ".")))
            (sleep! sleep)
            (recur (if (= :idle reason) (inc idle) 0)))))))
