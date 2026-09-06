# fraud-detection-service

![coverage](https://img.shields.io/badge/coverage-100%25-brightgreen) ![tier](https://img.shields.io/badge/tier-core-blue)

Rule-based transaction risk scoring (amount, geography, velocity, device and
channel rules) with fraud case management and SAR deadline tracking.

## Run / test

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8106
pytest
```

## Test layout

- `tests/test_scoring.py` — baseline single-transaction scoring signals.
- `tests/test_scoring_rules.py` — velocity, structuring-pattern, amount-vs-average,
  geo, device and channel rules; `AccountProfile` moving average and history pruning.
- `tests/test_cases.py` — `CaseManager` lifecycle: open/dedupe, assign, notes, close,
  SAR-required enforcement and SAR overdue detection.
- `tests/test_routers.py` — FastAPI `TestClient` coverage of `/api/v1/risk` and
  `/api/v1/cases`, including `CaseError` → HTTP status mapping.
