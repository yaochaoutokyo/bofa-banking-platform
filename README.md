# bofa-banking-platform

> **Internal demo repository.** Simulates a large-bank microservice monorepo
> with realistic but self-contained business logic (in-memory stores, no
> external network calls). Used to show how Devin raises test coverage on
> compliance-critical services ahead of a regulatory examination.

<!-- coverage-badges:start -->
![overall coverage](https://img.shields.io/badge/overall%20coverage-33%25%20avg-red) ![lines covered](https://img.shields.io/badge/lines%20covered-1040%20of%204465%20%2823%25%29-red)

**Compliance-critical:** ![transaction-service](https://img.shields.io/badge/transaction--service-16%25-red) ![auth-service](https://img.shields.io/badge/auth--service-19%25-red) ![payments-gateway](https://img.shields.io/badge/payments--gateway-tests%20not%20configured-lightgrey) ![pii-vault-service](https://img.shields.io/badge/pii--vault--service-tests%20not%20configured-lightgrey) ![audit-logging-service](https://img.shields.io/badge/audit--logging--service-26%25-orange) ![kyc-service](https://img.shields.io/badge/kyc--service-tests%20not%20configured-lightgrey)

**Core:** ![ledger-service](https://img.shields.io/badge/ledger--service-49%25-yellow) ![fraud-detection-service](https://img.shields.io/badge/fraud--detection--service-53%25-yellow) ![customer-portal-api](https://img.shields.io/badge/customer--portal--api-7%25-red) ![account-service](https://img.shields.io/badge/account--service-98%25-brightgreen)

**Supporting:** ![notification-service](https://img.shields.io/badge/notification--service-81%25-brightgreen) ![statement-service](https://img.shields.io/badge/statement--service-51%25-yellow)
<!-- coverage-badges:end -->

## The situation

| | |
|---|---|
| **Estate** | 12 microservices across Java/Spring Boot, Python/FastAPI and TypeScript/Express |
| **Overall test coverage** | ~30% average across services — and it is *uneven*: the best-tested service is account CRUD; the least-tested are transaction processing, authentication, PII tokenization, audit logging, payment routing and KYC |
| **Regulatory calendar** | OCC safety-and-soundness examination in 8 weeks. The examiners' request letter asks for evidence of automated control testing on transaction integrity, logical access, customer data protection and audit-trail integrity |
| **Team** | 6 engineers, 2 of whom joined in the last quarter. Testing has been carried as technical debt for three release cycles |
| **CI** | Only 5 of 12 services have a CI test job; 3 services have **no test runner configured at all** |

The scoreboard below is generated from real coverage reports by
`scripts/coverage-summary.py`. Nothing here is hand-typed.

## Current Test Coverage

<!-- coverage-table:start -->
| Service | Stack | Tier | Line coverage | Target | Gap | CI test job | Status |
|---|---|---|---:|---:|---:|:---:|---|
| **Overall — average across 12 services** | — | — | **33.3%** | 80% | -47 pts | 5 of 12 | 🔴 critical gap |
| **Overall — line-weighted** (1,040 / 4,465 lines) | — | — | **23.3%** | 80% | -57 pts | | |
| [`transaction-service`](services/transaction-service) | Java | compliance-critical | 16.1% | 90% | -74 pts | — | 🔴 critical gap |
| [`auth-service`](services/auth-service) | Java | compliance-critical | 18.9% | 90% | -71 pts | — | 🔴 critical gap |
| [`payments-gateway`](services/payments-gateway) | Java | compliance-critical | 0.0% | 90% | -90 pts | — | ⚫ no runner |
| [`ledger-service`](services/ledger-service) | Java | core | 48.8% | 80% | -31 pts | ✅ | 🟡 below target |
| [`pii-vault-service`](services/pii-vault-service) | Python | compliance-critical | 0.0% | 95% | -95 pts | — | ⚫ no runner |
| [`audit-logging-service`](services/audit-logging-service) | Python | compliance-critical | 25.9% | 95% | -69 pts | — | 🔴 critical gap |
| [`fraud-detection-service`](services/fraud-detection-service) | Python | core | 53.0% | 80% | -27 pts | ✅ | 🟡 below target |
| [`notification-service`](services/notification-service) | Python | supporting | 80.9% | 80% | -0 pts | ✅ | 🟢 at target |
| [`customer-portal-api`](services/customer-portal-api) | TypeScript | core | 6.8% | 80% | -73 pts | — | 🔴 critical gap |
| [`account-service`](services/account-service) | TypeScript | core | 97.9% | 80% | -0 pts | ✅ | 🟢 at target |
| [`statement-service`](services/statement-service) | TypeScript | supporting | 51.4% | 80% | -29 pts | ✅ | 🟡 below target |
| [`kyc-service`](services/kyc-service) | TypeScript | compliance-critical | 0.0% | 90% | -90 pts | — | ⚫ no runner |
<!-- coverage-table:end -->

Targets come from [`.codecov.yml`](.codecov.yml): 90–95% for compliance-critical
paths, 80% for everything else. "CI test job" marks the services that
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) actually runs — the rest
are unguarded on every merge.

## Compliance Readiness

The four control areas the examination will probe, with the coverage that
would be presented as evidence today. Mappings are detailed in
[`COMPLIANCE.md`](COMPLIANCE.md).

<!-- compliance-table:start -->
| Compliance-critical path | Services | Regulatory controls | Target | Actual | Gap | Exam readiness |
|---|---|---|---:|---:|---:|---|
| Transaction processing | `transaction-service`, `payments-gateway` | OCC exam · SOX 404 · Reg E · BSA/AML · PCI-DSS · NACHA · OCC payments risk | 90% | 10.6% | -79 pts | 🔴 Not exam-ready |
| Authentication | `auth-service` | FFIEC Authentication · SOX ITGC · PCI-DSS 8 | 90% | 18.9% | -71 pts | 🔴 Not exam-ready |
| PII handling | `pii-vault-service`, `kyc-service` | GLBA 501(b) · PCI-DSS 3.4 · Reg P · BSA/AML CIP · FinCEN CDD · OFAC | 95% | 0.0% | -95 pts | 🔴 Not exam-ready |
| Audit logging | `audit-logging-service` | SOX 404 · OCC auditability · BSA recordkeeping | 95% | 25.9% | -69 pts | 🔴 Not exam-ready |
<!-- compliance-table:end -->

## Regenerating the scoreboard

```bash
# run whichever suites you want fresh numbers for, e.g.
(cd services/ledger-service && mvn -q test)
(cd services/notification-service && pytest -q)
(cd services/account-service && npm test)

# then rewrite the badges + both tables in this README from the reports on disk
python3 scripts/coverage-summary.py --write
```

On every push to `main`, CI runs the same script against the reports from its
test jobs and commits the refreshed badges/tables automatically, so the
scoreboard above tracks `main` without manual steps.

Services without a fresh report fall back to `scripts/coverage-baseline.json`;
services without a test runner are counted at 0% against their real source
line count. `--json` prints the raw numbers, and
`--service <name> --github-summary` is what CI writes to `$GITHUB_STEP_SUMMARY`.

## Repository layout

```
services/
  transaction-service/     Java     transfers, FX conversion, idempotency, disputes, limits
  auth-service/            Java     JWT issue/verify, sessions, API keys, device trust, consent
  payments-gateway/        Java     ACH / wire / RTP / card routing, cutoffs, retries
  ledger-service/          Java     double-entry journal, trial balance, period close
  pii-vault-service/       Python   tokenization, masking, detokenization access policy
  audit-logging-service/   Python   hash-chained append-only trail, redaction, exam export
  fraud-detection-service/ Python   risk scoring, structuring detection, SAR case management
  notification-service/    Python   alerts over email / SMS / push, quiet hours, preferences
  customer-portal-api/     TS       profile, dashboard, bill pay, card controls
  account-service/         TS       account CRUD  <-- GOLDEN REFERENCE for testing conventions
  statement-service/       TS       statement generation, interest accrual, disclosures
  kyc-service/             TS       CIP, sanctions screening, beneficial ownership
scripts/coverage-summary.py       aggregates coverage into this README
.github/workflows/ci.yml          CI (5 of 12 services tested)
.codecov.yml                      per-service flags and targets
COMPLIANCE.md                     control-to-code mapping
DEMO.md                           timed demo script
CONTRIBUTING.md                   testing standard
```

## Running a service

| Stack | Commands |
|---|---|
| Java | `cd services/<svc> && mvn -q test` (JaCoCo report in `target/site/jacoco/`) |
| Python | `cd services/<svc> && pip install -r requirements.txt && pytest` (`coverage.xml`) |
| TypeScript | `cd services/<svc> && npm ci && npm run typecheck && npm test` (`coverage/`) |

`payments-gateway`, `pii-vault-service` and `kyc-service` compile and start
but have **no test command**.

## Conventions

* Testing standard: [`CONTRIBUTING.md`](CONTRIBUTING.md)
* Golden reference for TypeScript testing: [`services/account-service`](services/account-service)
* Reference pytest-cov setup: [`services/notification-service`](services/notification-service)
* Reference JUnit 5 + JaCoCo setup: [`services/ledger-service`](services/ledger-service)
* PR checklist: [`.github/pull_request_template.md`](.github/pull_request_template.md)
