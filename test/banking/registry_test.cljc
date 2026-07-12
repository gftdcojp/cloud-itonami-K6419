(ns banking.registry-test
  (:require [clojure.test :refer [deftest is]]
            [banking.registry :as r]))

;; ----------------------------- iban-checksum-invalid? -----------------------------
;; Test vectors are well-known published-example IBANs (ISO 13616 / ISO 7064
;; MOD 97-10), not fabricated -- the same honest-grounding discipline every
;; sibling actor's registry test suite uses for its own domain checksum.

(deftest valid-iban-passes
  (is (not (r/iban-checksum-invalid? {:iban "DE89370400440532013000"})) "Bundesbank published example")
  (is (not (r/iban-checksum-invalid? {:iban "GB82WEST12345698765432"})) "commonly-cited UK example")
  (is (not (r/iban-checksum-invalid? {:iban "FR1420041010050500013M02606"})) "commonly-cited FR example"))

(deftest valid-iban-with-spaces-still-passes
  (is (not (r/iban-checksum-invalid? {:iban "DE89 3704 0044 0532 0130 00"}))
      "whitespace is stripped before validation, the same as any real-world IBAN input source"))

(deftest corrupted-iban-fails
  (is (r/iban-checksum-invalid? {:iban "DE89370400440532013099"}) "check-digit-breaking corruption")
  (is (r/iban-checksum-invalid? {:iban "GB82WEST12345698765431"}) "last-digit corruption"))

(deftest malformed-iban-fails
  (is (r/iban-checksum-invalid? {:iban "not-an-iban"}))
  (is (r/iban-checksum-invalid? {:iban "DE89"}) "too short")
  (is (r/iban-checksum-invalid? {:iban ""})))

(deftest missing-iban-is-invalid
  (is (r/iban-checksum-invalid? {}))
  (is (r/iban-checksum-invalid? {:iban nil})))

;; ----------------------------- register-settlement -----------------------------

(deftest settlement-is-a-draft-not-a-real-settlement
  (let [result (r/register-settlement "account-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest settlement-assigns-settlement-number
  (let [result (r/register-settlement "account-1" "JPN" 7)]
    (is (= (get result "settlement_number") "JPN-SET-000007"))
    (is (= (get-in result ["record" "account_id"]) "account-1"))
    (is (= (get-in result ["record" "kind"]) "settlement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest settlement-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement "" "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement "account-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-settlement "account-1" "JPN" -1))))

;; ----------------------------- register-interbank-message -----------------------------

(deftest message-is-a-draft-not-a-real-dispatch
  (let [result (r/register-interbank-message "account-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest message-assigns-message-number
  (let [result (r/register-interbank-message "account-1" "JPN" 3)]
    (is (= (get result "message_number") "JPN-MSG-000003"))
    (is (= (get-in result ["record" "account_id"]) "account-1"))
    (is (= (get-in result ["record" "kind"]) "interbank-message-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest message-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-interbank-message "" "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-interbank-message "account-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-interbank-message "account-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-settlement "account-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-settlement "account-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SET-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SET-000001" (get-in hist2 [1 "record_id"])))))
