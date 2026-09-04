# fraud-detection-service

![coverage](https://img.shields.io/badge/coverage-~55%25-yellow) ![tier](https://img.shields.io/badge/tier-core-blue)

Rule-based transaction risk scoring (amount, geography, velocity, device and
channel rules) with fraud case management and SAR deadline tracking.

## Run / test

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8106
pytest
```

## Coverage gaps

Scoring rules for common signals are covered. Untested: velocity and
structuring-pattern rules (need multi-transaction history), profile
observation / moving average, and the entire case lifecycle
(assign, notes, close, SAR-required enforcement, overdue detection).
