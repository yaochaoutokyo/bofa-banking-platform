# pii-vault-service

![tests](https://img.shields.io/badge/tests-not%20configured-lightgrey) ![coverage](https://img.shields.io/badge/coverage-0%25-red) ![tier](https://img.shields.io/badge/tier-compliance--critical-red)

Tokenization, masking and access-controlled detokenization for customer PII
(SSN, card PAN, DOB, email, phone, account numbers, names).

**Regulatory scope:** GLBA Safeguards Rule (16 CFR 314), PCI-DSS 3.3/3.4,
OCC Heightened Standards (data protection).

## Run

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8104
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/tokens` | Validate + tokenize a PII value |
| POST | `/api/v1/tokens/detokenize` | Return raw value (policy-checked, logged) |
| GET | `/api/v1/tokens/{token}/masked` | Masked display form |
| DELETE | `/api/v1/tokens/{token}` | Revoke a token (owner only) |
| POST | `/api/v1/mask` | Mask a raw value without storing it |
| POST | `/api/v1/mask/record` | Mask every declared field of a record |
| GET/PUT | `/api/v1/access/policies` | Per-service detokenization policies |
| GET | `/api/v1/access/disclosures` | Disclosure log |

## Testing

There is no test runner configured for this service. `pyproject.toml` has no
pytest/pytest-cov dependency and there is no `tests/` directory.

Behaviour that is currently unverified (see comments in `app/services/*.py`):

- null / empty / oversized PII fields
- invalid SSN (area 000/666/9xx, group 00, serial 0000) and Luhn-failing PANs
- masking correctness per type (how many characters are revealed)
- detokenization access control per service and per PII type
- justification requirement for KYC lookups
- daily quota enforcement and disclosure logging
- owner scoping on token revocation

Use `services/notification-service` for a working `pytest-cov` setup to copy
from, and `services/account-service` for the team's testing conventions.
