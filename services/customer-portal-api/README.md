# customer-portal-api

![coverage](https://img.shields.io/badge/coverage-~7%25-red) ![tier](https://img.shields.io/badge/tier-customer--facing-blue)

Backend-for-frontend behind the online/mobile banking portal: profile
management, dashboard aggregation, bill pay (payees, scheduling, business-day
rolling, daily limits) and card controls (lock, international, merchant blocks,
lost/stolen replacement).

## Run / test

```bash
npm ci
npm run typecheck
npm test
```

## Coverage gaps

Two profile tests exist. Untested: email uniqueness and address rules on
profile update, agent redacted view, the entire bill-pay service (business-day
rolling, daily limit, cancellation cutoff, nightly batch), card controls
(unlock rate limit, locked-card guards, lost/stolen flow), dashboard
aggregation, all zod schemas and all routes including the session header
check.
