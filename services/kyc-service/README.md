# kyc-service

![tests](https://img.shields.io/badge/tests-not%20configured-lightgrey) ![coverage](https://img.shields.io/badge/coverage-0%25-red) ![tier](https://img.shields.io/badge/tier-compliance--critical-critical)

Customer Identification Program (CIP) decisioning for individuals and legal
entities: identity data-element validation, OFAC-style sanctions screening,
jurisdiction risk, PEP flags, beneficial-ownership (FinCEN CDD Rule) and
risk-tier assignment.

Regulatory scope: BSA/AML — 31 CFR 1020.220 (CIP), 31 CFR 1010.230 (CDD),
OFAC sanctions programs, USA PATRIOT Act §326.

## Run

```bash
npm ci
npm run typecheck
npm run build && npm start
```

## Tests

**There is no test runner configured for this service.** `package.json` has no
`test` script, no Jest/Vitest dependency, and there is no `tests/` directory.
Coverage: 0%.

Consequently none of the following has ever been verified by an automated test:
minor/future/leap-day DOBs, SSN vs ITIN rules, PO Box rejection, expired
documents on the boundary day, sanctioned-jurisdiction failure, fuzzy
sanctions matching and DOB tolerance, PEP/EDD routing, beneficial-owner 25%
threshold, ownership >100%, or the HTTP error mapping.
