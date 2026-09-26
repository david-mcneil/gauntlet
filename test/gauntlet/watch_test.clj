(ns gauntlet.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [gauntlet.db :as db]
            [gauntlet.test-support :as ts]
            [gauntlet.watch :as watch]))

(def opts {:poll-ms 1000 :limit-wait-ms 900000})

(deftest next-action-test
  (is (= {:sleep 1000 :reason :idle} (watch/next-action nil opts)))
  (is (= {:continue true} (watch/next-action {:item-id 1 :status "merged"} opts)))
  (is (= {:continue true} (watch/next-action {:item-id 1 :status "needs_human"} opts)))
  (is (= {:sleep 900000 :reason :usage-limit}
         (watch/next-action {:item-id 1 :stopped :usage-limit} opts)))
  (is (= :budget (:reason (watch/next-action {:item-id 1 :stopped :budget} opts))))
  (is (< 0 (:sleep (watch/next-action {:item-id 1 :stopped :budget} opts)) (* 25 3600 1000)))
  (is (:exit (watch/next-action {:item-id 1 :stopped :auth} opts))))

(deftest watch-loop-test
  (let [ctx     (ts/test-context {})
        ds      (:ds ctx)
        results (atom [])
        sleeps  (atom [])
        added?  (atom false)]
    (db/add-item! ds {:title "First" :spec "first"})
    (testing "processes queued items, then picks up an item added while idle"
      (watch/watch (constantly ctx)
                   (assoc opts
                          :report   #(swap! results conj %)
                          :log      (fn [_])
                          :max-idle 2
                          :sleep!   (fn [ms]
                                      (swap! sleeps conj ms)
                                      (when-not @added?
                                        (reset! added? true)
                                        (db/add-item! ds {:title "Second" :spec "second"})))))
      (is (= ["merged" "merged"] (map :status @results)))
      (is (= ["merged" "merged"] (map :status (db/items ds))))
      (is (= [1000 1000] @sleeps)))))

(deftest watch-stops-on-rejected-credential-test
  (let [ctx (ts/test-context {:worker (fn [_] {:exit 1 :stopped :auth})})]
    (db/add-item! (:ds ctx) {:title "t" :spec "s"})
    (is (re-find #"bb login"
                 (watch/watch (constantly ctx)
                              (assoc opts :report (fn [_]) :log (fn [_]) :sleep! (fn [_])))))))
