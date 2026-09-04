# statement-service

![coverage](https://img.shields.io/badge/coverage-~45%25-yellow) ![tier](https://img.shields.io/badge/tier-supporting-blue)

Generates periodic account statements (JSON / text / CSV) from a ledger feed,
with Reg DD / Reg E disclosures and an interest-accrual preview.

## Run / test

```bash
npm ci
npm run typecheck
npm test
```

## Coverage gaps

Statement arithmetic and money formatting are covered. Untested: interest
accrual and APY tiers (`src/services/interest.ts`), text/CSV rendering,
the ledger feed, and all HTTP routes / error mapping.
