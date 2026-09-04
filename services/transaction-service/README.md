# transaction-service

Core money-movement service. Executes account-to-account transfers with
currency conversion, idempotency keys, per-transfer and daily limits, and
reversal.

**Compliance tier:** critical (OCC safety & soundness, SOX 404 ITGC, Reg E).

## Run

```bash
mvn spring-boot:run          # http://localhost:8081
mvn test                     # JUnit 5 + JaCoCo -> target/site/jacoco/index.html
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/transactions/transfer` | Execute a transfer |
| POST | `/api/v1/transactions/{id}/reverse` | Reverse a completed transfer |
| GET | `/api/v1/transactions/{id}` | Fetch a transaction |
| GET | `/api/v1/transactions/account/{accountId}` | List transactions for an account |

## Test status

Only the happy path (`TransactionServiceTest`) is covered. Validation and
error-handling branches in `TransactionService.validate`, `CurrencyConverter`,
the reversal path, and the controller's error mapping have no tests.
