(ns cinemaops.governor-test
  "Pure unit tests of `cinemaops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-
  test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [cinemaops.governor :as gov]
            [cinemaops.advisor :as advisor]
            [cinemaops.store :as store]))

(def screening-1 {:screening-id "screening-1" :film "Reel One" :registered? true :verified? true})
(def screening-3 {:screening-id "screening-3" :film "Silent Aperture" :registered? true :verified? false})

(defn- clean-proposal [op screening-id]
  {:op op :screening-id screening-id :summary "s" :rationale "routine operations coordination"
   :cites [screening-id] :effect :propose :value {} :confidence 0.85})

(deftest screening-unregistered-is-hard
  (testing "no screening record at all -> HARD hold"
    (let [s (store/mem-store {"screening-1" screening-1})
          verdict (gov/check {} nil (clean-proposal :log-screening-record "unknown-screening") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:screening-unverified} (map :rule (:violations verdict)))))))

(deftest screening-unverified-is-hard
  (testing "screening registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"screening-3" screening-3})
          verdict (gov/check {} nil (clean-proposal :log-screening-record "screening-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:screening-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"screening-1" screening-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-screening-operation "screening-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"screening-1" screening-1})
          verdict (gov/check {} nil (clean-proposal :override-admission-policy "screening-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest evacuation-override-content-is-hard-and-permanent
  (testing "a proposal whose rationale directly finalizes an evacuation override is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"screening-1" screening-1})
          poisoned (assoc (clean-proposal :log-screening-record "screening-1")
                          :rationale "decided to override the evacuation order and keep the theater open during the evacuation"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest age-rating-override-content-is-hard
  (testing "a proposal directly finalizing an age-rating admission override is HARD-blocked, same as evacuation override"
    (let [s (store/mem-store {"screening-1" screening-1})
          poisoned (assoc (clean-proposal :schedule-screening-operation "screening-1")
                          :summary "will override the age-rating admission check for this showtime"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest projector-actuation-content-is-hard
  (testing "a proposal touching direct projection/booth equipment actuation is HARD-blocked"
    (let [s (store/mem-store {"screening-1" screening-1})
          poisoned (assoc (clean-proposal :coordinate-print-delivery "screening-1")
                          :value {:plan "directly control the projector to start the show early"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest fire-safety-system-control-content-is-hard
  (testing "a proposal touching direct fire/life-safety system control is HARD-blocked"
    (let [s (store/mem-store {"screening-1" screening-1})
          poisoned (assoc (clean-proposal :flag-patron-safety-concern "screening-1")
                          :rationale "recommend to silence the fire alarm system and override the sprinkler system"
                          :value {:concern "false alarm suspected"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-patron-safety-concern-is-not-scope-excluded
  (testing "flagging observed fire-alarm/egress/age-rating-admission concerns as a PATRON SAFETY CONCERN (not a finalized override decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"screening-1" screening-1})
          concern (assoc (clean-proposal :flag-patron-safety-concern "screening-1")
                         :rationale "劇場内で観察された安全に関する事実（火災警報の作動、避難経路の混雑、入場時の年齢確認の懸念等）の報告。常に人間の確認・対応が必要。"
                         :value {:concern "fire alarm briefly sounded near screen 3 exit corridor, and a patron appeared to be under the minimum age for the R-rated screening"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (fire alarms, egress congestion, age-rating admission concerns) is exactly what this op exists to surface"))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "CRITICAL regression: every op the default mock advisor can produce, on a clean happy-path request, must NEVER trip scope-exclusion on its own default rationale/summary/disclaimer text -- a bare-noun-phrased scope-excluded-terms entry has previously self-blocked sibling actors' own happy paths in this fleet; scope-excluded-terms here are phrased as finalization/execution ACTIONS, not bare nouns, specifically to avoid this"
    (let [s (store/mem-store {"screening-1" screening-1})]
      (doseq [op [:log-screening-record :schedule-screening-operation
                  :coordinate-print-delivery :flag-patron-safety-concern]]
        (let [proposal (advisor/infer nil {:op op :screening-id "screening-1"
                                            :patch {:attendance 50 :concern "fire alarm briefly sounded, no injuries"}})
              verdict (gov/check {} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "op " op "'s own default mock-advisor proposal must never self-trip scope-exclusion"))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "op " op " must be in the closed allowlist")))))))
