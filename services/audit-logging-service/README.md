# audit-logging-service

![coverage](https://img.shields.io/badge/coverage-~20%25-red) ![tier](https://img.shields.io/badge/tier-compliance--critical-red)

Append-only, SHA-256 hash-chained audit trail for regulated events emitted by
every other service. Provides chain verification, dead-letter replay, and
retention / legal-hold management.

**Regulatory scope:** SOX 404 (evidence of control operation), OCC
examination auditability, BSA record-keeping (7-year retention).

## Run

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8105
```

## Test

```bash
pip install -r requirements.txt
pytest
```

`pytest-cov` is configured in `pyproject.toml`; coverage is written to
`coverage.xml`.

## Coverage gaps

The single existing test appends one well-formed event. Unverified behaviour
(see the module docstrings under `app/services/`):

- write failures: sink unavailable / sink full, dead-letter capture and replay
- ordering and hash-chain continuity under concurrent appends
- immutability (PUT/DELETE must be rejected; tampering must break `verify_chain`)
- missing actor on regulated categories; placeholder actors; naive/future/stale timestamps
- redaction of secrets, SSNs, PANs, JWTs and emails inside `details`
- retention reductions without approval; legal holds; archival cut-offs
