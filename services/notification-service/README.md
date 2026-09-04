# notification-service

![coverage](https://img.shields.io/badge/coverage-~80%25-brightgreen) ![tier](https://img.shields.io/badge/tier-supporting-blue)

Customer alerts over email / SMS / push with per-customer preferences, quiet
hours, marketing opt-in, deduplication and strict templating.

## Run / test

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8107
pytest
```

Tests use a frozen clock (`tests/conftest.py`) so quiet-hours behaviour is
deterministic. This is the reference `pytest-cov` setup for the Python
services; `pii-vault-service` and `audit-logging-service` should copy the
`[tool.pytest.ini_options]` block from `pyproject.toml`.
