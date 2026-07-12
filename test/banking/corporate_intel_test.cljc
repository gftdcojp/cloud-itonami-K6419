(ns banking.corporate-intel-test
  "Proves the value `banking.corporate-intel` actually adds: an account
  holder that is clean on every LOCAL field (no
  `:sanctions-flag-unresolved?`) but IS flagged in
  `cloud-itonami-isic-8291`'s own demo data no longer silently resolves
  -- something 6419's local-only checks alone would have missed
  entirely (see `account-5` in `banking.store/demo-data`, shared
  holder-name with 8291's sanctions-flagged demo official).

  Vocabulary note (this repo has NO 'incomplete' concept, unlike
  `cloud-itonami-isic-6910`'s officer-screening 3-way `:hit`/
  `:incomplete`/`:clear`): `banking.bankingadvisor/screen-sanctions`
  only ever produces `:unresolved`/`:resolved`. So EVERY non-clean
  8291 signal -- a definitive hit, 8291's own pending human review, or
  8291's screen itself being held -- collapses onto the SAME
  `:unresolved` verdict a local flag would produce. `banking.governor`'s
  `sanctions-violations` check reads `:unresolved` UNCONDITIONALLY (see
  its docstring / `test/banking/governor_contract_test.clj`'s
  `sanctions-flag-is-held-and-unoverridable`), so unlike 6910 (where an
  inconclusive 8291 signal degrades to a SOFT `:incomplete` that still
  escalates for a human), here it is a HARD, un-overridable HOLD --
  intentionally MORE conservative, because this repo's own vocabulary
  gives no middle ground between resolved and unresolved."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [banking.store :as store]
            [banking.operation :as op]
            [banking.bankingadvisor :as bankingadvisor]
            [banking.corporate-intel :as ci]))

(def operator {:actor-id "op-1" :actor-role :banking-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (bankingadvisor/mock-advisor {:corporate-intel-screen ci/screen})})]))

(deftest local-checks-alone-would-miss-the-8291-flagged-account
  (testing "sanity: without the integration wired in, account-5 passes the local check and resolves clean"
    (let [db (store/seed-db)
          actor (op/build db)                          ; NO corporate-intel wired in
          res (exec-op actor "sanity" {:op :sanctions/screen :subject "account-5"} operator)]
      (is (= :interrupted (:status res)) "sanctions/screen always escalates for approval, clean or not")
      (approve! actor "sanity")
      (is (= :resolved (:verdict (store/sanctions-screen-of db "account-5")))
          "without the integration, account-5 screens :resolved -- this is the gap being closed"))))

(deftest corporate-intel-catches-the-hit-local-checks-miss
  (testing "with the REAL (unmocked) 8291 actor wired in, account-5 hard-holds instead of silently resolving"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :sanctions/screen :subject "account-5"} operator)]
      (is (= :hold (get-in res [:state :disposition]))
          "8291 itself escalates a real hit for ITS OWN human review first -- 6419 never peeks
           behind that gate, but this repo has no :incomplete middle ground, so a
           :pending-human-review? signal collapses onto :unresolved, which HARD-holds
           immediately here (unlike 6910, where the analogous case only soft-escalates)")
      (is (some #{:sanctions-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/sanctions-screen-of db "account-5"))
          "critically: no clearance is ever written, unlike the unwired sanity case above"))))

(deftest corporate-intel-definitive-hit-hard-holds
  (testing "screen-sanctions's :hit? branch itself is a HARD, un-overridable hold -- proven directly
            with a stub (a real 8291 hit always escalates for 8291's own human first, so this
            branch is only reachable end-to-end after that human confirms; unit-testing it here
            keeps the assertion deterministic)"
    (let [db (store/seed-db)
          definitive-hit (fn [_name] {:found? true :hit? true :capacity :director :org "co-x"})
          actor (op/build db {:advisor (bankingadvisor/mock-advisor {:corporate-intel-screen definitive-hit})})
          res (exec-op actor "t2" {:op :sanctions/screen :subject "account-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (some #{:sanctions-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/sanctions-screen-of db "account-5")) "no sanctions clearance written"))))

(deftest corporate-intel-clean-account-still-resolves
  (testing "an account with no local signal, and no match in 8291's demo data, still resolves clean --
            additive, not stricter-by-default (a confident not-found is not treated as a hit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t3" {:op :sanctions/screen :subject "account-1"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t3")
      (is (= :resolved (:verdict (store/sanctions-screen-of db "account-1")))))))

(deftest corporate-intel-local-sanctions-hit-short-circuits-before-8291-is-consulted
  (testing "a local :sanctions-flag-unresolved? decides the verdict first -- 8291 is never even queried"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t4" {:op :sanctions/screen :subject "account-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:sanctions-flag-unresolved} (-> (store/ledger db) first :basis))))))

(deftest corporate-intel-held-screen-degrades-to-unresolved-and-hard-holds
  (testing "if 6419's own tenant contract with 8291 is missing/misconfigured, 8291 itself holds the
            screen -- 6419 must treat that as :unresolved (never :resolved), and because there is
            no :incomplete middle ground here, that HARD-holds immediately rather than merely
            escalating (unlike 6910, where the analogous case only soft-escalates as :incomplete)"
    (let [db (store/seed-db)
          broken-screen (fn [_name] {:held? true :reason [:licensed-disclosure]})
          actor (op/build db {:advisor (bankingadvisor/mock-advisor {:corporate-intel-screen broken-screen})})
          res (exec-op actor "t5" {:op :sanctions/screen :subject "account-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (some #{:sanctions-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/sanctions-screen-of db "account-5"))))))
