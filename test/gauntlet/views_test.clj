(ns gauntlet.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gauntlet.db :as db]
            [gauntlet.pipeline :as pipeline]
            [gauntlet.runtime.fake :as fake]
            [gauntlet.test-support :as ts]
            [gauntlet.views :as views]))

(defn- trace [{:keys [ds]} id]
  (views/trace-lines (db/item ds id) (db/runs ds id)
                     (map #(assoc % :findings (db/findings ds (:id %))) (db/verdicts ds id))
                     (db/events ds id)
                     false))

(defn- reject [request]
  (fake/write-json! (get-in request [:dirs :out]) "verdict.json"
                    {:verdict "reject" :summary "No."
                     :findings [{:severity "blocking" :rule "A1" :message "Fix this."}]})
  {:exit 0})

(deftest trace-test
  (let [n   (atom 0)
        ctx (ts/test-context {:alpha (fn [r] (if (= 1 (swap! n inc)) (reject r) (fake/approve r)))})
        id  (db/add-item! (:ds ctx) {:title "Feature" :spec "s"})
        _   (pipeline/run-next ctx)
        out (trace ctx id)
        txt (str/join "\n" out)]
    (testing "one flow line per pass, with the return to the worker marked"
      (is (some #(= "   1  worker → alpha ✗  ↺" %) out))
      (is (some #(= "   2  worker → alpha ✓ → beta ✓ ⇒ merged" %) out)))
    (testing "details show the worker summary and the rejection's findings"
      (is (str/includes? txt "\"Fake worker change.\""))
      (is (str/includes? txt "[blocking] A1 Fix this."))
      (is (str/includes? txt "↺ back to the worker with 1 blocking finding(s)")))))

(deftest trace-after-requeue-test
  (let [ctx (ts/test-context {:alpha reject} {:max-rounds 1})
        id  (db/add-item! (:ds ctx) {:title "Feature" :spec "s"})
        _   (pipeline/run-next ctx)
        _   (db/update-item! (:ds ctx) id {:status "implementing" :round 1 :note nil})
        _   (pipeline/run-next ctx)
        out (trace ctx id)]
    (is (some #(= "   1  worker → alpha ✗ ⚠ needs you ↻ requeued" %) out))
    (is (some #(= "   2  worker → alpha ✗ ⚠ needs you" %) out))))
