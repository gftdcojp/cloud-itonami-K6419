# Operator Quickstart — Community Monetary Intermediation

Shortest path from clone to a verified local dry-run for **ISIC 6419** (`cloud-itonami-isic-6419`).

## Who this is for

Licensed deposit-taking institutions, credit unions, and community banking operators who want to fork, deploy, and run their own governed settlement and interbank-message workflows, without surrendering ledger control to external SaaS.

## Prerequisites

- Clojure 1.12+ (`clojure --version`)
- Java 17+
- Git

No invented metrics; this is a governed OSS blueprint, not a hosted SaaS demo.

## 1. Clone

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6419.git
cd cloud-itonami-isic-6419
```

## 2. Run tests

```bash
clojure -M:test
```

Expect green if maturity is `implemented`. Fix failures before operating.

## 3. Open the product face

```bash
open docs/index.html   # or: python3 -m http.server -d docs 8080
```

Publish: enable GitHub Pages on `main` `/docs`, or any static host.

## 4. The Governor: holds unsafe commits

- **Blueprint key:** `monetary-intermediation-governor`
- **Source:** `src/banking/governor.cljc` — implements spec-basis, evidence-completeness, IBAN checksum (ISO 7064 MOD 97-10), and sanctions-resolution checks
- **Pattern:** advisor proposes → governor validates against ground truth → phase gates human approval for actuation (settle/dispatch) → immutable audit ledger

## 5. Claim / go-live

- Free claim funnel: https://itonami.cloud/isco-1212/
- Paid path docs: https://itonami.cloud/docs/go-live.md
- Blueprint: `blueprint.edn`

## Constraints

- Do not invent users/revenue numbers for marketing
- No force-push; keep AGPL headers
- Secrets stay out of this repo
