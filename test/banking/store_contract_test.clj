(ns banking.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [banking.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sato Ichiro" (:holder-name (store/account s "account-1"))))
      (is (= "JPN" (:jurisdiction (store/account s "account-1"))))
      (is (= "DE89370400440532013000" (:iban (store/account s "account-1"))))
      (is (false? (:sanctions-flag-unresolved? (store/account s "account-1"))))
      (is (= "DE89370400440532013099" (:iban (store/account s "account-3"))))
      (is (true? (:sanctions-flag-unresolved? (store/account s "account-4"))))
      (is (false? (:settlement-posted? (store/account s "account-1"))))
      (is (false? (:interbank-message-dispatched? (store/account s "account-1"))))
      (is (= ["account-1" "account-2" "account-3" "account-4" "account-5"]
             (mapv :id (store/all-accounts s))))
      (is (nil? (store/sanctions-screen-of s "account-1")))
      (is (nil? (store/compliance-of s "account-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/settlement-history s)))
      (is (= [] (store/interbank-message-history s)))
      (is (zero? (store/next-settlement-sequence s "JPN")))
      (is (zero? (store/next-message-sequence s "JPN")))
      (is (false? (store/account-already-settled? s "account-1")))
      (is (false? (store/account-already-dispatched? s "account-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :account/upsert
                                 :value {:id "account-1" :holder-name "Sato Ichiro"}})
        (is (= "Sato Ichiro" (:holder-name (store/account s "account-1"))))
        (is (= "DE89370400440532013000" (:iban (store/account s "account-1"))) "unrelated field preserved"))
      (testing "compliance / sanctions-screen payloads commit and read back"
        (store/commit-record! s {:effect :compliance/set :path ["account-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/compliance-of s "account-1")))
        (store/commit-record! s {:effect :sanctions-screen/set :path ["account-1"]
                                 :payload {:account-id "account-1" :verdict :resolved}})
        (is (= {:account-id "account-1" :verdict :resolved} (store/sanctions-screen-of s "account-1"))))
      (testing "settlement drafts a record and advances the sequence"
        (store/commit-record! s {:effect :account/mark-settled :path ["account-1"]})
        (is (= "JPN-SET-000000" (get (first (store/settlement-history s)) "record_id")))
        (is (= "settlement-draft" (get (first (store/settlement-history s)) "kind")))
        (is (true? (:settlement-posted? (store/account s "account-1"))))
        (is (= 1 (count (store/settlement-history s))))
        (is (= 1 (store/next-settlement-sequence s "JPN")))
        (is (true? (store/account-already-settled? s "account-1")))
        (is (false? (store/account-already-settled? s "account-2"))))
      (testing "interbank message drafts a record and advances the sequence"
        (store/commit-record! s {:effect :account/mark-dispatched :path ["account-1"]})
        (is (= "JPN-MSG-000000" (get (first (store/interbank-message-history s)) "record_id")))
        (is (= "interbank-message-draft" (get (first (store/interbank-message-history s)) "kind")))
        (is (true? (:interbank-message-dispatched? (store/account s "account-1"))))
        (is (= 1 (count (store/interbank-message-history s))))
        (is (= 1 (store/next-message-sequence s "JPN")))
        (is (true? (store/account-already-dispatched? s "account-1")))
        (is (false? (store/account-already-dispatched? s "account-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/account s "nope")))
    (is (= [] (store/all-accounts s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/settlement-history s)))
    (is (= [] (store/interbank-message-history s)))
    (is (zero? (store/next-settlement-sequence s "JPN")))
    (is (zero? (store/next-message-sequence s "JPN")))
    (store/with-accounts s {"x" {:id "x" :holder-name "n"
                               :iban "DE89370400440532013000"
                               :sanctions-flag-unresolved? false
                               :settlement-posted? false :interbank-message-dispatched? false
                               :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:holder-name (store/account s "x"))))))
