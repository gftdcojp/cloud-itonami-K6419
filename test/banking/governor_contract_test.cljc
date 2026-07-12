(ns banking.governor-contract-test
  "The governor contract as executable tests -- the banking analog of
  `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`. The
  single invariant under test:

    BankingOps-LLM never posts a settlement or dispatches an interbank
    message the Monetary Intermediation Governor would reject,
    `:actuation/post-settlement`/`:actuation/dispatch-interbank-
    message` NEVER auto-commit at any phase, `:account/intake` (no
    direct capital risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [banking.store :as store]
            [banking.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :banking-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a compliance
  assessment on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :compliance/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through sanctions screening -> approve, leaving a
  screening on file. Only safe to call for an account whose sanctions
  status has already resolved -- an unresolved flag HARD-holds the
  screen itself (see `sanctions-flag-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :sanctions/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :account/intake :subject "account-1"
                   :patch {:id "account-1" :holder-name "Sato Ichiro"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sato Ichiro" (:holder-name (store/account db "account-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest compliance-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :compliance/verify :subject "account-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/compliance-of db "account-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a compliance/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :compliance/verify :subject "account-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/compliance-of db "account-1")) "no compliance assessment written"))))

(deftest post-settlement-without-compliance-is-held
  (testing "actuation/post-settlement before any compliance verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/post-settlement :subject "account-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest iban-checksum-invalid-is-held
  (testing "an account whose own IBAN fails its own ISO 7064 MOD 97-10 checksum -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "account-3")
          res (exec-op actor "t5" {:op :actuation/post-settlement :subject "account-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:iban-checksum-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/settlement-history db))))))

(deftest sanctions-flag-is-held-and-unoverridable
  (testing "an unresolved sanctions flag on an account -> HOLD, and never reaches request-approval -- exercised via :sanctions/screen DIRECTLY, not via the actuation op against an unscreened account (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's, salon's, entertainment's, casework's, hospital's, facility's, school's, association's, leasing's, behavioral's, secondary's, card's, water's, telecom's, aerospace's, recovery's, consulting's, union's, congregation's, fab's, energy's, care's, navigator's and learning's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :sanctions/screen :subject "account-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/sanctions-screen-of db "account-4")) "no clearance written"))))

(deftest post-settlement-always-escalates-then-human-decides
  (testing "a clean, fully-assessed account still ALWAYS interrupts for human approval -- actuation/post-settlement is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "account-1")
          r1 (exec-op actor "t7" {:op :actuation/post-settlement :subject "account-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, settlement record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:settlement-posted? (store/account db "account-1"))))
          (is (= 1 (count (store/settlement-history db))) "one draft settlement record"))))))

(deftest dispatch-interbank-message-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, sanctions-resolved account still ALWAYS interrupts for human approval -- actuation/dispatch-interbank-message is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "account-1")
          _ (screen! actor "t8pre2" "account-1")
          r1 (exec-op actor "t8" {:op :actuation/dispatch-interbank-message :subject "account-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, interbank-message record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:interbank-message-dispatched? (store/account db "account-1"))))
          (is (= 1 (count (store/interbank-message-history db))) "one draft interbank-message record"))))))

(deftest post-settlement-double-settlement-is-held
  (testing "posting the same account's settlement twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "account-1")
          _ (exec-op actor "t9a" {:op :actuation/post-settlement :subject "account-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/post-settlement :subject "account-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-settled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/settlement-history db))) "still only the one earlier settlement"))))

(deftest dispatch-interbank-message-double-dispatch-is-held
  (testing "dispatching the same account's interbank message twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "account-1")
          _ (screen! actor "t10pre2" "account-1")
          _ (exec-op actor "t10a" {:op :actuation/dispatch-interbank-message :subject "account-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/dispatch-interbank-message :subject "account-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/interbank-message-history db))) "still only the one earlier dispatch"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :account/intake :subject "account-1"
                          :patch {:id "account-1" :holder-name "Sato Ichiro"}} operator)
      (exec-op actor "b" {:op :compliance/verify :subject "account-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
