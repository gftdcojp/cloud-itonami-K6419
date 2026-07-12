(ns banking.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/post-settlement`/`:actuation/dispatch-
  interbank-message` must NEVER be a member of any phase's `:auto`
  set."
  (:require [clojure.test :refer [deftest is testing]]
            [banking.phase :as phase]))

(deftest post-settlement-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real settlement posting"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/post-settlement))
          (str "phase " n " must not auto-commit :actuation/post-settlement")))))

(deftest dispatch-interbank-message-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real interbank-message dispatch"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/dispatch-interbank-message))
          (str "phase " n " must not auto-commit :actuation/dispatch-interbank-message")))))

(deftest sanctions-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :sanctions/screen))
          (str "phase " n " must not auto-commit :sanctions/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":account/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:account/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :account/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/post-settlement} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/dispatch-interbank-message} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :account/intake} :commit)))))
