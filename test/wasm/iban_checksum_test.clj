(ns wasm.iban-checksum-test
  "Hosts wasm/iban_checksum.wasm (compiled from wasm/iban_checksum.kotoba,
  see wasm/README.md) via kototama.tender -- proves banking.registry's
  ISO 7064 MOD 97-10 IBAN checksum recompute (`iban-checksum-invalid?` in
  src/banking/registry.cljc, independently re-verified by
  `banking.governor`'s `iban-checksum-invalid-violations`) runs as a real
  WASM guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the digit-count and digit-sequence inputs are written into
  the guest's exported linear memory at fixed offsets before calling
  main() -- see wasm/iban_checksum.kotoba's ns-adjacent header comment for
  the offset layout.

  This test file does the SAME host-side text preprocessing
  `banking.registry/iban-numeric-string` does (move the first 4 characters
  to the end, then substitute each letter A-Z for its two-digit ISO 7064
  MOD 97-10 code, digits passing through unchanged) -- independently
  reimplemented here rather than reaching into `banking.registry`'s private
  vars, because that preprocessing is host-side text munging, NOT part of
  the ported wasm guest (see wasm/README.md \"Why the source differs\").
  The guest itself only ever sees the resulting digit sequence."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

;; ---- host-side IBAN -> digit-sequence preprocessing (test-only; mirrors
;; banking.registry/iban-numeric-string, kept independent on purpose) ----

(def ^:private alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- char->digits [c]
  (if (Character/isDigit ^char c)
    (str c)
    (str (+ 10 (str/index-of alphabet (str c))))))

(defn- iban-digit-string
  "Move the first 4 characters to the end, then substitute letters for
  digits per ISO 7064 MOD 97-10 -- the standard IBAN validation
  rearrangement. Returns the resulting all-digit string."
  [iban]
  (let [cleaned (str/replace (str/upper-case iban) #"\s" "")
        rearranged (str (subs cleaned 4) (subs cleaned 0 4))]
    (apply str (map char->digits rearranged))))

;; ---- wasm host plumbing ----

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/iban_checksum.wasm"))))

(defn- run-iban-checksum-valid? [iban]
  (let [digits (iban-digit-string iban)
        instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 (count digits))
    (dorun
     (map-indexed (fn [i ch]
                    (.writeByte memory (+ 4 i) (byte (Character/digit ^char ch 10))))
                  digits))
    (tender/call-main instance)))

;; ---- real, independently-verified IBAN test vectors ----
;;
;; hand/REPL-verified against the actual `banking.registry/iban-checksum-
;; invalid?` (and its private `mod-97`) before writing this test: all five
;; "valid" vectors below recompute to mod-97 == 1 (checksum valid) via the
;; real Clojure implementation; the two "invalid" vectors are the same
;; well-known IBANs with one BBAN digit corrupted, which the real
;; implementation confirms recompute to mod-97 == 28 (checksum invalid).
;; These are standard example/reference IBANs (ISO 13616 / SWIFT IBAN
;; registry publish GB82 WEST.../DE89 3704.../CH93 0076... as canonical
;; worked examples).

(deftest iban-checksum-wasm-approves-well-known-valid-ibans
  (testing "UK example IBAN -- GB82 WEST 1234 5698 7654 32 (28 digits after expansion)"
    (is (= 1 (run-iban-checksum-valid? "GB82 WEST 1234 5698 7654 32"))))
  (testing "Germany example IBAN -- DE89 3704 0044 0532 0130 00 (24 digits after expansion)"
    (is (= 1 (run-iban-checksum-valid? "DE89 3704 0044 0532 0130 00"))))
  (testing "France example IBAN -- FR14 2004 1010 0505 0001 3M02 606 (30 digits after expansion, exercises a letter mid-BBAN)"
    (is (= 1 (run-iban-checksum-valid? "FR14 2004 1010 0505 0001 3M02 606"))))
  (testing "UK example IBAN #2 -- GB29 NWBK 6016 1331 9268 19 (28 digits after expansion)"
    (is (= 1 (run-iban-checksum-valid? "GB29 NWBK 6016 1331 9268 19"))))
  (testing "Switzerland example IBAN -- CH93 0076 2011 6238 5295 7 (23 digits after expansion -- shortest vector, exercises variable digit-count via genuine recursion, not a fixed unroll)"
    (is (= 1 (run-iban-checksum-valid? "CH93 0076 2011 6238 5295 7")))))

(deftest iban-checksum-wasm-rejects-corrupted-ibans
  (testing "GB82 WEST...32 with the final check-adjacent BBAN digit flipped 2->3 -- real mod-97 recomputes to 28, not 1"
    (is (= 0 (run-iban-checksum-valid? "GB82 WEST 1234 5698 7654 33"))))
  (testing "DE89 3704...00 with the final BBAN digit flipped 0->1 -- real mod-97 recomputes to 28, not 1"
    (is (= 0 (run-iban-checksum-valid? "DE89 3704 0044 0532 0130 01")))))

(deftest iban-checksum-wasm-handles-degenerate-empty-digit-sequence
  (testing "digit-count 0 -- the fold never iterates, remainder stays its 0 seed, 0 != 1 -> correctly rejects rather than crashing"
    (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
          memory (.memory instance)]
      (.writeI32 memory 0 0)
      (is (= 0 (tender/call-main instance))))))
