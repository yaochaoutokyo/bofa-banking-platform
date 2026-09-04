# ledger-service

Double-entry general ledger. Accepts journal entries, enforces that debits
equal credits, maintains account balances, and produces a trial balance.

**Compliance tier:** core (SOX 404 financial reporting).

## Run

```bash
mvn spring-boot:run          # http://localhost:8084
mvn test                     # JUnit 5 + JaCoCo -> target/site/jacoco/index.html
```

## Test status

`LedgerServiceTest` covers balanced posting, one unbalanced rejection, and
journal retrieval. Remaining validation branches (negative amounts, precision,
unknown accounts, too few lines), the controller, and duplicate-account
registration are untested.
