(ns gauntlet.runtime.fake
  "A runtime that runs Clojure functions in place of agents. Used by the tests
  and for trying the pipeline without spending anything.

  Behaviors are functions of the run request. They can write to the request's
  directories and make commits in (:work dirs), as a real agent would."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [gauntlet.git :as git]
            [gauntlet.runtime :as runtime]))

(defn write-json! [dir filename data]
  (spit (io/file dir filename) (json/write-str data)))

(defn commit-change!
  "Appends a line to a file in the work checkout and commits it."
  [{:keys [dirs]} filename line]
  (let [f (io/file (:work dirs) filename)]
    (spit f (str line "\n") :append true)
    (git/git! (:work dirs) "add" filename)
    (git/git! (:work dirs) "commit" "--quiet" "-m" line)))

(defn worker-done
  "A worker behavior that commits one change and reports done."
  [request]
  (commit-change! request "FAKE-WORK.md" (str "change at " (System/nanoTime)))
  (write-json! (get-in request [:dirs :out]) "result.json"
               {:status "done" :summary "Fake worker change."})
  {:exit 0})

(defn approve [request]
  (write-json! (get-in request [:dirs :out]) "verdict.json"
               {:verdict "approve" :summary "Fake approval." :findings []})
  {:exit 0})

(defrecord FakeRuntime [behaviors commands calls]
  runtime/AgentRuntime
  (run-agent [_ request]
    (swap! calls conj request)
    (let [behavior (or (get behaviors (:agent request))
                       (if (= :implement (:role request)) worker-done approve))]
      (merge {:exit 0 :cost-usd 0.0} (behavior request))))
  (run-command [_ request]
    (swap! calls conj request)
    (if-let [command (get commands (:input request))]
      (command request)
      {:exit 0 :out (str "fake output for " (name (:input request)) "\n")})))

(defn fake-runtime
  "behaviors: {agent-id (fn [request] result)}; agents not listed use
  worker-done or approve. commands: {input-keyword (fn [request] result)}."
  ([] (fake-runtime {} {}))
  ([behaviors] (fake-runtime behaviors {}))
  ([behaviors commands] (->FakeRuntime behaviors commands (atom []))))
