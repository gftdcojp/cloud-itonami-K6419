# cloud-itonami-isic-6419

Open Business Blueprint for **ISIC Rev.5 6419**: other monetary
intermediation (receiving deposits and extending credit — banks,
savings banks, credit unions, postal giro).

This repository publishes a community-monetary-intermediation actor --
account intake, AML/KYC compliance assessment, sanctions screening,
settlement posting and interbank-message dispatch -- as an OSS
business that any qualified banking operator can fork, deploy, run,
improve and sell, so a community never surrenders its money and
ledgers to a closed SaaS.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103),
[`9602`](https://github.com/cloud-itonami/cloud-itonami-isic-9602),
[`9000`](https://github.com/cloud-itonami/cloud-itonami-isic-9000),
[`8890`](https://github.com/cloud-itonami/cloud-itonami-isic-8890),
[`8610`](https://github.com/cloud-itonami/cloud-itonami-isic-8610),
[`9311`](https://github.com/cloud-itonami/cloud-itonami-isic-9311),
[`8510`](https://github.com/cloud-itonami/cloud-itonami-isic-8510),
[`9412`](https://github.com/cloud-itonami/cloud-itonami-isic-9412),
[`6491`](https://github.com/cloud-itonami/cloud-itonami-isic-6491),
[`8720`](https://github.com/cloud-itonami/cloud-itonami-isic-8720),
[`8521`](https://github.com/cloud-itonami/cloud-itonami-isic-8521),
[`6619`](https://github.com/cloud-itonami/cloud-itonami-isic-6619),
[`3600`](https://github.com/cloud-itonami/cloud-itonami-isic-3600),
[`6190`](https://github.com/cloud-itonami/cloud-itonami-isic-6190),
[`3030`](https://github.com/cloud-itonami/cloud-itonami-isic-3030),
[`3830`](https://github.com/cloud-itonami/cloud-itonami-isic-3830),
[`7020`](https://github.com/cloud-itonami/cloud-itonami-isic-7020),
[`9420`](https://github.com/cloud-itonami/cloud-itonami-isic-9420),
[`9491`](https://github.com/cloud-itonami/cloud-itonami-isic-9491),
[`2610`](https://github.com/cloud-itonami/cloud-itonami-isic-2610),
[`3512`](https://github.com/cloud-itonami/cloud-itonami-isic-3512),
[`8810`](https://github.com/cloud-itonami/cloud-itonami-isic-8810),
[`8691`](https://github.com/cloud-itonami/cloud-itonami-isic-8691),
[`8569`](https://github.com/cloud-itonami/cloud-itonami-isic-8569)) --
here it is **Banking Advisor ⊣ Monetary Intermediation Governor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> account-intake summary, normalizing records, and checking whether an
> account's own IBAN actually passes its own ISO 7064 MOD 97-10
> checksum -- but it has **no notion of which jurisdiction's AML/KYC
> law is official, no license to post a real settlement or dispatch a
> real interbank message, and no way to know on its own whether a
> sanctions flag against an account has actually stayed unresolved**.
> Letting it post a settlement or dispatch a message directly invites
> fabricated regulatory citations, an unbalanced or checksum-invalid
> IBAN reaching a real transfer, and a sanctions hit being quietly
> overlooked -- and liability, and financial-crime risk, for whoever
> runs it. This project seals the BankingOps-LLM into a single node
> and wraps it with an independent **Monetary Intermediation
> Governor**, a human **approval workflow**, and an immutable **audit
> ledger**.

## Scope: what this actor does and does not do

This actor covers account intake through AML/KYC compliance
assessment, sanctions screening, settlement posting and interbank-
message dispatch. It does **not**, by itself, hold any banking or
payments license required to operate as a deposit-taking institution
in a given jurisdiction, and it does not claim to. It also does
**not** model a real core-banking/clearing-network system --
`banking.registry/iban-checksum-invalid?` implements the REAL ISO
7064 MOD 97-10 algorithm against the account's own IBAN field, but
this actor does not itself connect to SWIFT/ISO 20022 or any real
settlement rail. Whoever deploys and operates a live instance (a
licensed deposit-taking institution) supplies any jurisdiction-
specific license, the real clearing-network membership and the real
core-banking integrations, and bears that jurisdiction's liability --
the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch.

### Actuation

**Posting a real settlement or dispatching a real interbank message is
never autonomous, at any phase, by construction.** Two independent
layers enforce this (`banking.governor`'s `:actuation/post-
settlement`/`:actuation/dispatch-interbank-message` high-stakes gate
and `banking.phase`'s phase table, which never puts `:actuation/post-
settlement`/`:actuation/dispatch-interbank-message` in any phase's
`:auto` set) -- see `banking.phase`'s docstring and `test/banking/
phase_test.clj`'s `post-settlement-never-auto-at-any-phase`/
`dispatch-interbank-message-never-auto-at-any-phase`. The actor may
draft, check and recommend; a human banking operator is always the
one who actually posts a settlement or dispatches an interbank
message. Like `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/
`9200`/`9521`/`8730`/`9102`/`9103`/`8890`/`8610`/`8510`/`9412`/`8720`/
`8521`/`6619`/`3600`/`6190`/`3030`/`3830`/`9420`/`9491`/`2610`/`3512`/
`8810`/`8691`/`8569`, this actor has TWO actuation events, both
POSITIVE (posting/dispatching a real record), matching the majority
pattern in this fleet (`3600`/`6190` are the fleet's two NEGATIVE-
actuation exceptions).

## The core contract

```
account intake + jurisdiction facts (banking.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Banking      │ ─────────────▶ │ Monetary Intermediation       │  (independent system)
   │ Advisor      │  + citations    │ Governor:                    │
   │ (sealed)     │                 │ spec-basis · evidence-       │
   └──────────────┘         commit ◀────┼──────────▶ hold │ incomplete ·
                                 │             │           │ iban-checksum-
                           record + ledger  escalate ─▶ human   invalid (ISO 7064
                                             (ALWAYS for         MOD 97-10) ·
                                              :actuation/post-           sanctions-flag-
                                              settlement /               unresolved
                                              :actuation/dispatch-        (unconditional) ·
                                              interbank-message)          already-settled/
                                                                           -dispatched
```

**The BankingOps-LLM never posts a settlement or dispatches an
interbank message the Monetary Intermediation Governor would reject,
and never does so without a human sign-off.** Hard violations
(fabricated regulatory requirements; unsupported evidence; an IBAN
checksum failure; an unresolved sanctions flag; a double posting or
dispatch) force **hold** and *cannot* be approved past; a clean
settlement/dispatch proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean dual-actuation lifecycle + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a vault-servicing and cash-
handling robot handles the physical movement and custody of value
under the actor, gated by the independent **Monetary Intermediation
Governor**. The governor never dispatches hardware itself; `:high`/
`:safety-critical` actions require human sign-off.

A live sample of the operator console is rendered in
[docs/samples/operator-console.html](docs/samples/operator-console.html)
-- pure-data HTML output of `kotoba.banking.ui`.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Monetary Intermediation Governor, settlement + interbank-message draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6419`), which names
[`kotoba-lang/banking`](https://github.com/kotoba-lang/banking) (accounts,
IBAN, double-entry ledger, clearing) and
[`kotoba-lang/swift`](https://github.com/kotoba-lang/swift) (SWIFT MT /
ISO 20022, BIC) as the required capability libraries for a production
deployment. This R0 governed-actor implementation does not add either
as a code dependency -- following this fleet's established convention
of implementing the specific ground-truth checks a governor needs
directly (here, a REAL ISO 7064 MOD 97-10 IBAN-checksum
implementation in `banking.registry`, not a fabricated placeholder),
the same posture every sibling actor without a bespoke capability-lib
dependency takes. A production operator wiring this actor to a real
core-banking/clearing-network integration would swap in
`kotoba-lang/banking`/`kotoba-lang/swift` at that layer.

## Layout

| File | Role |
|---|---|
| `src/banking/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate settlement/interbank-message history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded account, and the double-actuation guards check dedicated `:settlement-posted?`/`:interbank-message-dispatched?` booleans rather than a `:status` value |
| `src/banking/registry.cljc` | Settlement + interbank-message draft records, plus `iban-checksum-invalid?` -- the FIRST instance of this fleet's checksum/format-validity check family, a REAL ISO 7064 MOD 97-10 implementation (portable `.cljc`, no JVM-only `Character` interop) |
| `src/banking/facts.cljc` | Per-jurisdiction AML/KYC catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/banking/bankingadvisor.cljc` | **BankingOps-LLM** -- `mock-advisor` ‖ `llm-advisor`; intake/compliance-verification/sanctions-screening/settlement-posting/interbank-message-dispatch proposals |
| `src/banking/governor.cljc` | **Monetary Intermediation Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · iban-checksum-invalid, pure ground-truth checksum recompute · sanctions-flag-unresolved, unconditional evaluation, REUSING the exact `sanctions-violations` concept `underwriting`/`casualty`/`vcfund`/`formation`/`realty` already established -- sanctions screening is a genuinely shared, industry-standard AML concern across financial-services verticals) + already-settled/already-dispatched guards + 1 soft (confidence/actuation gate) |
| `src/banking/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (both settlement posting and interbank-message dispatch always human; account intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/banking/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/banking/corporate_intel.cljc` | optional cross-reference into [`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291)'s `:disclosure/screen-name` -- catches an account holder clean on every LOCAL field but flagged in 8291's own sourced PEP/sanctions data; wired into `screen-sanctions` via an injected fn, default is a no-op so every prior caller's behavior is unchanged unless explicitly opted in. Since this actor's own vocabulary has no `:incomplete` middle ground, any non-clean 8291 signal (a hit, 8291's own pending human review, or 8291's screen being held) collapses onto the same `:unresolved` verdict a local flag would produce -- HARD-holding immediately, more conservative than the analogous `cloud-itonami-isic-6910` integration |
| `src/banking/sim.cljc` | demo driver |
| `test/banking/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage · corporate-intelligence integration |
| `wasm/iban_checksum.kotoba` | PoC: a WASM-compiled (`kotoba-lang/kotoba` -> `kotoba-lang/kototama`'s `actor:host` ABI) port of `registry.cljc`'s `iban-checksum-invalid?` ISO 7064 MOD 97-10 recompute, via a genuinely recursive fold (not a fixed unroll) -- see `wasm/README.md` for scope, the input/output ABI, and what's out of scope (the letter/rearrangement text preprocessing, Store, the account lookup) |

## Business-process coverage (honest)

This actor covers account intake through AML/KYC compliance
assessment, sanctions screening, settlement posting and interbank-
message dispatch -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Account intake + per-jurisdiction AML/KYC checklisting, HARD-gated on an official spec-basis citation (`:account/intake`/`:compliance/verify`) | Real core-banking/clearing-network integration, real custody of funds (see `banking.facts`'s docstring) |
| Sanctions screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:sanctions/screen`) | Any lending/credit-decisioning judgment itself -- deliberately outside this actor's competence |
| Settlement posting, HARD-gated on full evidence and the account's own IBAN checksum, plus a double-posting guard (`:actuation/post-settlement`) | |
| Interbank-message dispatch, HARD-gated on full evidence and sanctions resolution, plus a double-dispatch guard (`:actuation/dispatch-interbank-message`) | |
| Immutable audit ledger for every intake/verification/screening/settlement/dispatch decision | |

Extending coverage is additive: add the next gate (e.g. a lending
credit-decision check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`banking.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `banking.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `banking.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `BankingOps-LLM` + `Monetary Intermediation
Governor` run as real, tested code (see `Run` above), promoted from
the originally-published `:blueprint`-tier scaffold, modeled closely
on the fifty-two prior actors' architecture. See `docs/adr/0001-
architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
