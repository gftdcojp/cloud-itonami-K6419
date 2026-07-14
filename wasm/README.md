# wasm/ — kotoba-wasm deployment of the IBAN checksum recompute

`iban_checksum.kotoba` is a port of `banking.registry/iban-checksum-invalid?`'s
ISO 7064 MOD 97-10 IBAN check-digit recompute (see `src/banking/registry.cljc`
lines ~79-105, independently re-verified by `banking.governor`'s
`iban-checksum-invalid-violations`, `src/banking/governor.cljc` lines ~167-178)
into the minimal `.kotoba` language subset, compiled to a real WASM module via
`kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/iban_checksum_test.clj`).

This follows the same `kotoba wasm emit` -> `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`,
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba`, and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established
(ADR-2607062330 addendum 5) — the sixth sibling actor's hot-path decision
function ported to real WASM, and the FIRST of the six to be a checksum
recompute rather than a ceiling/threshold/ratio/formula comparison (see this
repo's own `src/banking/registry.cljc` ns docstring: this actor was
deliberately built around ISO 7064 MOD 97-10 as its first checksum/format-
validity check family member).

## Why this actor's port looks different from every prior sibling: genuine recursion

Every one of the four prior wasm ports (`6492`/`6511`/`6512`/`6630`) is
straight-line `if`/`let` arithmetic — none of them call a user-defined
function, let alone call one recursively. The IBAN mod-97 algorithm is a
**fold over a variable-length digit sequence** (a real IBAN's rearranged,
letter-expanded numeric form ranges roughly 15–66 digits depending on
country and BBAN content), which straight-line `if`/`let` code cannot express
without either a genuine loop or unrolling to some fixed maximum length.
`.kotoba` has no `loop`/`recur` special form. Before writing this port, it was
genuinely unconfirmed whether the compiler's WASM code-generator
(`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj`) supports
a plain user-defined function calling itself by name (as opposed to
`call-indirect`, which targets a WASM table and exists for a different
purpose).

**It does.** Reading `compile-wasm-expr` (specifically `wasm-binary`, which
builds `fn-indexes` — a name -> function-index map covering every top-level
`defn` — *before* compiling any function body, then passes that same map as
compile context into every body, including the function's own) shows a
symbol that isn't a special form, arithmetic op, or host import falls
through to a lookup in that map and compiles to a plain WASM `call`
instruction (opcode `0x10`) against the resolved index. Since a function's
own index is already in the map when its own body is compiled, a direct
self-call is ordinary, unremarkable WASM — no special "loop" construct is
needed because WASM function calls are just calls; recursion is exactly as
supported as any other call. This was verified empirically in this pass with
a standalone probe (`sum-to`, summing `0..n` via `(sum-to (+ acc idx) (+ idx
1) n)`) that compiled via `kotoba wasm emit` and, run through
`kototama.tender`, correctly returned `45` for `n=10` and `2278` for `n=68`
(the deepest recursion this port's own max digit-count needs) — confirming
both that the compiler accepts it and that the JVM host (Chicory-backed
`kototama.tender`) executes the resulting `call`-instruction recursion
correctly to the depth this port requires. No prior merged example in this
fleet exercises this path; this is the first.

Given that, `mod97-fold` is written as the natural, faithful reduction of
`banking.registry/mod-97`'s own `reduce` — an accumulator-and-index
recursive fold — rather than forcing a fixed-length unroll with a padding
scheme. This avoids the fixed-length approach's real correctness hazard: an
unrolled version needs every unused trailing digit slot to be a true
mathematical no-op on the fold, and naively zero-padding is **not** a
no-op in general (`(mod (+ (* r 10) 0) 97)` changes `r` unless `r` is
already `0`), so a fixed-length unroll would need per-length capping logic
this actor's variable BBAN lengths make awkward. Recursion sidesteps the
whole problem: the fold only ever runs exactly `digit-count` times.

## Why the source otherwise differs from `banking.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec`, plus (confirmed in this
pass) plain calls to other top-level `defn`s including recursive self-calls
(no `and`/`or`/`when`/`pos?`/`neg?`, unlike the broader tree-walking
interpreter, same finding every prior sibling documents). The port therefore:

- Ports ONLY the pure numeric fold — the streaming mod-97 remainder
  recurrence itself — never the text preprocessing (`iban-numeric-string`'s
  first-4-chars-to-end rearrangement + letter A-Z -> two-digit substitution),
  the IBAN shape regex, the `nil?` guard, or the `store/account` lookup, all
  of which stay in Clojure and never get ported (no strings, no regex, no
  maps, no `nil` in the wasm-compilable subset). The host (in this pass,
  `test/wasm/iban_checksum_test.clj` itself — see its own docstring) performs
  that preprocessing and writes the RESULT (a sequence of decimal digit
  VALUES, not ASCII characters) into the guest's linear memory. This mirrors
  every prior sibling's discipline of porting only the pure ground-truth
  arithmetic/comparison core, never the store lookups or op-dispatch (see
  `cloud-itonami-isic-6512`/`-6630`'s READMEs, "Why the source differs").
- Represents the digit sequence as raw memory bytes (one decimal digit VALUE
  0-9 per byte, read via `mem-byte-at`) rather than a string — no strings in
  the wasm-compilable subset, and a digit VALUE (not the ASCII codepoint) is
  exactly what the fold arithmetic needs, so the host does that conversion
  once instead of the guest repeatedly subtracting `'0'` per digit.
  `digit-count` (how many of the written bytes are meaningful) is a fourth
  memory input alongside the digit bytes themselves — the ABI's only "shape"
  concession — needed because `.kotoba` has no way to detect a sentinel/
  terminator value in the wasm-compilable subset.
- Drops the `nil? iban` guard and the shape regex entirely — same posture
  `fee_accrual.kotoba` documents for dropping precondition `throw`s: a WASM
  export can't throw, and `banking.governor`'s own `iban-checksum-invalid-
  violations` only calls this recompute path after other layers have already
  established the account record is well-formed; validating format/shape
  stays the real `iban-checksum-invalid?`'s job, unported.
- Compares the recomputed fold result against `1` with a plain `=` — ISO
  7064 MOD 97-10's own validity criterion (a valid IBAN's rearranged numeric
  form is congruent to 1 mod 97) is already an exact integer equality in the
  real implementation, so no float-epsilon or rounding concern applies here
  (unlike `fee_accrual.kotoba`'s `close?` -> exact-`=` substitution).
- Inverts the polarity relative to `registry.cljc`'s `iban-checksum-
  invalid?`: this module's `main` returns `1` when the checksum is VALID
  (i.e. NOT invalid) and `0` when it is invalid — the same "is this OK"
  polarity convention `claim_coverage.kotoba`/`fee_accrual.kotoba`/
  `affordability.kotoba` all use for a boolean-shaped WASM export.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead — the same convention
every prior sibling's port uses. A host writes:

| offset | field | notes |
|--------|-------|-------|
| 0 | `digit-count` (i32) | number of meaningful digit bytes that follow. Real IBANs (15-34 chars) rearrange + letter-expand to roughly 15-66 digits depending on country/BBAN content — `mod97-fold` recurses exactly `digit-count` times, so no fixed cap is baked into the guest itself |
| 4 .. 4+digit-count-1 | one decimal digit VALUE per byte (`0`-`9`, not ASCII `'0'`-`'9'`) | the ISO 7064 MOD 97-10 rearranged + letter-expanded numeric form of the IBAN, computed by the HOST (`iban-digit-string` in the test file) — the guest never sees IBAN letters or the rearrangement, only the resulting digit sequence |

`main()` returns `1` (the digit sequence's mod-97 fold is congruent to 1 —
checksum VALID) or `0` (any other remainder — checksum INVALID,
`banking.governor`'s `:iban-checksum-invalid` HARD violation). Both memory
regions are well below `heap-base` (2048), so they never collide with
anything the compiler itself places in memory.

## Test vectors

`test/wasm/iban_checksum_test.clj` uses five standard example/reference
IBANs (the ISO 13616 / SWIFT IBAN registry's own canonical worked examples
for GB, DE, FR, CH) as the "valid" vectors, plus two of those same IBANs
with one BBAN digit hand-corrupted as the "invalid" vectors. Every vector's
expected outcome was cross-checked in a REPL against the real
`banking.registry/iban-checksum-invalid?` (and its private `mod-97`) before
being hardcoded into the test — see the test file's own docstring for the
exact verification transcript description. The Switzerland vector (23
digits after expansion, the shortest of the five) and the UK/Germany/France
vectors (24, 28, and 30 digits respectively) deliberately span different
digit-counts to exercise the variable-length recursion path, not just one
fixed length — something a fixed-unroll design could not have covered
without a separate unroll per length.

## Known scope limits (honest)

- **No i64 promotion needed, unlike `fee_accrual.kotoba`.** The fold's
  intermediate value (`remainder * 10 + digit`) never exceeds `969` (`96 *
  10 + 9`) before the `mod 97`, nowhere near the i32 ceiling — this port has
  no analogous overflow caveat to document.
- **Recursion depth is bounded by realistic IBAN length (~66), not
  arbitrary input.** The guest performs no bounds-checking on `digit-count`
  itself (same "the guest only ever sees facts a governor already
  validated" posture `underwriting_decision.kotoba` documents) — a
  pathologically large `digit-count` from a misbehaving host would recurse
  proportionally deep. This was not a concern for any prior sibling port
  (all straight-line, no recursion, no equivalent hazard) and is a genuine
  first for this port; not hardened against in this pass.
- Fleet deployment: not attempted in this pass — see
  `cloud-itonami-isic-6492`/`-6511`/`-6512`/`-6630` for the established
  pattern.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6419/wasm/iban_checksum.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6419/wasm/iban_checksum.wasm --json
```

(NOTE: `bin/kotoba-clj` resolves its own home directory via a physical, not
logical, `cd`, so invoking it with paths relative to a *symlinked* sibling
checkout can silently resolve against the real shared checkout instead of an
isolated worktree — use absolute paths for the `.kotoba` source and
`--output` when working from a worktree, as this pass did.)
