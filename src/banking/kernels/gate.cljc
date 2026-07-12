(ns banking.kernels.gate
  "Safety kernel for the Monetary Intermediation Governor + phase gate
  — the decision CORE of `banking.governor/check` and
  `banking.phase/gate`, extracted into the safe-kotoba subset
  (cloud-itonami kernels discipline, ADR-0016 / superproject
  ADR-2607101200).

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`banking.governor`, `banking.phase`) reduce their inputs to
  flags/codes at the boundary and map the result codes back to
  keywords. `.kotoba`/wasm emission is deliberately NOT wired yet
  (owner decision 2026-07-12: ClojureScript + kotoba-datomic first);
  staying inside the subset is what keeps that door open without a
  rewrite.

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    op          0 reserved read — this domain's `read-ops` set is EMPTY
                  (banking.phase), so no façade op ever maps to 0; the
                  kernel gives code 0 no write rights and it holds
                  exactly like an unknown op (fail-closed). If
                  `read-ops` ever gains a member, the kernel needs a
                  read pass-through branch FIRST.
                1 :account/intake
                2 :compliance/verify
                3 :sanctions/screen
                4 :actuation/post-settlement
                5 :actuation/dispatch-interbank-message
                6+ unknown write (never enabled)
    phase       0..3 (anything else: no writes at all — the façade
                normalizes unknown phases to its own default BEFORE the
                kernel, so an out-of-range phase reaching the kernel is
                a bug and fails closed)
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    disposition 0 commit  1 escalate  2 hold
    reason      0 none  1 phase-disabled  2 phase-approval

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. Ops 4 and 5 (the two real
  banking actuations: posting a settlement / dispatching an interbank
  message) are auto-enabled at NO phase — the same structural invariant
  the phase table and the governor's actuation gate state
  independently."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))
(defn or6 [a b c d e f] (or2 (or3 a b c) (or3 d e f)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation flag is set:
  spec-basis missing / evidence incomplete / IBAN checksum invalid /
  sanctions flag unresolved / already settled / already dispatched."
  [spec-missing evidence-incomplete iban-invalid sanctions-unresolved
   already-settled already-dispatched]
  (or6 (norm-flag spec-missing)
       (norm-flag evidence-incomplete)
       (norm-flag iban-invalid)
       (norm-flag sanctions-unresolved)
       (norm-flag already-settled)
       (norm-flag already-dispatched)))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [spec-missing evidence-incomplete iban-invalid sanctions-unresolved
   already-settled already-dispatched confidence-x100 actuation]
  (if (= 1 (hard-violation spec-missing evidence-incomplete iban-invalid
                           sanctions-unresolved already-settled
                           already-dispatched))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column).
  Code 0 (reserved read — unused, `read-ops` is empty in this domain)
  is enabled nowhere, so it holds fail-closed like an unknown op."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 0)))
      (if (= phase 3)
        (if (= op 1)
          1
          (if (= op 2)
            1
            (if (= op 3) 1 (if (= op 4) 1 (if (= op 5) 1 0)))))
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly one cell is ever 1: phase 3 x :account/intake.
  Ops 4 and 5 (:actuation/post-settlement /
  :actuation/dispatch-interbank-message) are 0 at every phase —
  permanent structural fact, not a rollout milestone. Op 3
  (:sanctions/screen) is likewise 0 at every phase."
  [phase op]
  (if (= phase 3) (if (= op 1) 1 0) 0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `banking.phase/gate`: governor
  hold always wins; a write not enabled at this phase holds (this
  domain has NO read ops, so there is no read pass-through branch —
  code 0 falls into the not-enabled hold like any unknown op); a
  governor-clean write without auto rights escalates; otherwise the
  governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= 0 (op-write-enabled phase op))
      2
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 1)
        governor-disposition))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= 0 (op-write-enabled phase op))
      1
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 2)
        0))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict [spec evid iban sanc setl disp conf act expected]
  (if (= (verdict-code spec evid iban sanc setl disp conf act) expected) 1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(def battery-case-count 43)

(defn battery-pass-count []
  (+
   ;; -- verdict: each hard flag alone dominates (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 100 0 0)
   (check-verdict 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 0 100 0 2)
   (check-verdict 0 0 0 0 0 1 100 0 2)
   ;; -- verdict: hard flag combos still hard-hold
   (check-verdict 1 0 0 1 0 0 100 0 2)
   (check-verdict 0 1 0 0 1 1 100 0 2)
   (check-verdict 1 1 1 1 1 1 100 0 2)
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags normalize to violation (fail-closed)
   (check-verdict 7 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 5 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 100 9 1)
   ;; -- phase: governor hold always wins
   (check-phase 3 1 2 2 0)
   ;; -- phase: code 0 (reserved read, read-ops EMPTY here) never writes
   (check-phase 3 0 0 2 1)
   (check-phase 0 0 1 2 1)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 1 3 0 2 1)
   (check-phase 2 4 0 2 1)
   (check-phase 2 5 0 2 1)
   (check-phase 3 6 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 2 0 1 2)
   (check-phase 2 3 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 4 0 1 2)
   (check-phase 3 5 0 1 2)
   ;; -- phase: the single auto cell
   (check-phase 3 1 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 1 1 1 0)
   (check-phase 2 1 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)))
