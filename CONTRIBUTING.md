# Contributing

## Testing standard

All services in this monorepo are expected to meet the following standard. Today,
many do not — see the coverage tables in the [README](README.md). Closing that gap
is the team's top engineering priority ahead of the OCC examination.

### Coverage targets

| Service tier | Line coverage target | Branch coverage target |
|---|---|---|
| Compliance-critical (transaction, auth, payments, PII, audit, KYC) | **90%** | **80%** |
| Core business (ledger, fraud, customer portal, statements) | 80% | 70% |
| Supporting (account, notification) | 80% | 70% |

### What every service must have

1. A configured test runner that runs with a single command:
   - Java: `mvn test` (JUnit 5 + JaCoCo)
   - Python: `pytest --cov` (pytest + pytest-cov)
   - TypeScript: `npm test` (Jest with `--coverage`)
2. Happy-path tests for every public endpoint.
3. **Edge-case tests for every validation rule and error branch.** Untested error
   handling is the single largest category of audit findings we carry.
4. Tests must be deterministic: no network, no wall-clock dependence without a
   fake clock, no shared mutable state between tests.
5. A CI job in `.github/workflows/ci.yml` that runs the tests and uploads coverage
   with a per-service Codecov flag.

### Reference implementation

`services/account-service` is the **golden reference** for testing conventions:
dependency injection through constructors, an in-memory repository that is
replaced in tests, a `tests/helpers` module for fixtures and fakes, and
`describe`/`it` blocks grouped by behavior with explicit edge cases.

When adding tests to a service that has none, follow the patterns in
`account-service` and translate them idiomatically to the target language.

### Pull requests

- Fill in the test-coverage checklist in the PR template.
- Coverage must not decrease for any service touched by the PR.
- Compliance-critical services require review from `@bofa-platform/risk-controls`
  (see `CODEOWNERS`).
- CI must be green before merge. Do not bypass required checks.

## Local development

Each service is self-contained and runs with in-memory stores — no databases,
brokers, or external APIs are needed.

```bash
# Java
cd services/ledger-service && mvn test

# Python
cd services/notification-service && pip install -e ".[dev]" && pytest

# TypeScript
cd services/account-service && npm ci && npm test
```

Regenerate the README coverage tables after running tests:

```bash
python3 scripts/coverage-summary.py --write
```
