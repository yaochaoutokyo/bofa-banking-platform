# account-service — GOLDEN REFERENCE

![coverage](https://img.shields.io/badge/coverage-~90%25-brightgreen) ![tier](https://img.shields.io/badge/tier-core-blue) ![reference](https://img.shields.io/badge/testing-golden%20reference-blueviolet)

Deposit account CRUD and lifecycle (open, update, freeze/unfreeze, close,
dormancy sweep).

**This service defines the team's testing conventions.** When bootstrapping
tests for another TypeScript service, instruct Devin to *"follow the patterns
in `services/account-service`"*.

## Run / test

```bash
npm ci
npm run typecheck
npm test            # jest with coverage; thresholds enforced in jest.config.js
```

## Testing conventions

| Convention | Where | Why |
|---|---|---|
| Constructor dependency injection — repository, event publisher, clock, ID generator are all passed in | `src/services/accountService.ts` | No module-level singletons; every collaborator can be replaced in a test |
| Interfaces for collaborators (`AccountRepository`, `AccountEventPublisher`, `Clock`, `IdGenerator`) | `src/domain.ts`, `src/repositories/` | Tests depend on contracts, not implementations |
| Composition root separated from the HTTP server | `src/app.ts` vs `src/index.ts` | `createApp(service)` is testable with supertest; `index.ts` is excluded from coverage |
| One shared harness: `createHarness()`, `buildAccount()`, `FrozenClock`, `SequentialIds` | `tests/helpers/testHarness.ts` | Deterministic time and IDs; fixtures override only what matters |
| Side effects are `jest.fn()` mocks; assert interactions with `toHaveBeenCalledWith(expect.objectContaining(...))` | `tests/unit/accountService.test.ts` | Verify events without a real bus |
| Table-driven edge cases via `it.each` | unit tests | Every validation branch and state transition gets a row |
| Domain errors carry `code` + HTTP `status`; tests assert with `rejects.toMatchObject({ code, status })` | `src/domain.ts` | Error contracts are explicit and stable |
| Unit tests for service + schema, integration tests for routes via supertest | `tests/unit`, `tests/integration` | Fast feedback, plus HTTP contract coverage |
| Coverage thresholds fail the build | `jest.config.js` | Coverage cannot silently regress |

## Structure

```
src/
  domain.ts               types, AccountError, Clock/IdGenerator interfaces
  repositories/           AccountRepository interface + in-memory impl
  services/               AccountService (business rules)
  validation/             zod schemas + error formatting
  routes/                 express router + error handler
  app.ts                  createApp(service)
  index.ts                process entry point (wires real collaborators)
tests/
  helpers/testHarness.ts  createHarness, buildAccount, FrozenClock, SequentialIds
  unit/                   service + schema tests
  integration/            supertest route tests
```
