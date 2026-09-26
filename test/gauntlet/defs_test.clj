(ns gauntlet.defs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gauntlet.defs :as defs]
            [gauntlet.test-support :as ts]))

(deftest load-defs-test
  (let [repo (ts/defs-repo!)
        d    (defs/load-defs repo "main")]
    (testing "one worker and reviewers in :order"
      (is (= :worker (:id (defs/worker d))))
      (is (= [:alpha :beta] (map :id (defs/reviewers d)))))

    (testing "context lists paths in order, prompt.md first within a directory"
      (let [ctx (get-in d [:agents :alpha :context])]
        (is (< (str/index-of ctx "protocol text")
               (str/index-of ctx "alpha prompt")
               (str/index-of ctx "alpha rules")))
        (is (str/includes? ctx "<file path=\"agents/alpha/rules.md\">"))))))

(deftest versions-track-context-paths-test
  (let [repo   (ts/defs-repo!)
        before (defs/load-defs repo "main")]
    (ts/write-file! repo "agents/alpha/rules.md" "changed rules")
    (ts/commit-all! repo "change alpha")
    (let [after (defs/load-defs repo "main")
          v     (fn [d id] (get-in d [:agents id :version]))]
      (testing "editing an agent's directory changes only that agent's version"
        (is (not= (v before :alpha) (v after :alpha)))
        (is (= (v before :beta) (v after :beta)))
        (is (= (v before :worker) (v after :worker)))))

    (ts/write-file! repo "shared/protocol.md" "changed protocol")
    (ts/commit-all! repo "change protocol")
    (let [before (defs/load-defs repo "HEAD~1")
          after  (defs/load-defs repo "main")]
      (testing "editing a shared file changes every agent that lists it"
        (is (every? #(not= (get-in before [:agents % :version])
                           (get-in after [:agents % :version]))
                    [:worker :alpha :beta]))))))

(deftest reads-committed-state-only-test
  (let [repo   (ts/defs-repo!)
        before (defs/load-defs repo "main")]
    (ts/write-file! repo "agents/alpha/rules.md" "uncommitted edit")
    (is (= (get-in before [:agents :alpha]) (get-in (defs/load-defs repo "main") [:agents :alpha])))))

(deftest invalid-registry-test
  (let [repo (ts/defs-repo!)]
    (ts/write-file! repo "agents.edn"
                    "{:agents {:worker {:role :implement :model \"m\" :max-budget-usd 1
                                        :context-paths [\"missing\"]}}}")
    (ts/commit-all! repo "break")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"context path missing does not exist"
                          (defs/load-defs repo "main")))))
