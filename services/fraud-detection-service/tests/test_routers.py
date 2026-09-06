"""HTTP-level tests against the FastAPI app.

`engine` and `cases` are module-level singletons shared across requests, so every
test uses its own account / transaction ids via the `ids` fixture.
"""

from datetime import datetime, timedelta, timezone
from itertools import count

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.cases import SAR_DEADLINE, cases

_counter = count(1)

ANALYST = "analyst.a"


class Ids:
    def __init__(self, n: int) -> None:
        self.account = f"ACC-R{n}"
        self._txn = count(1)

    def txn(self) -> str:
        return f"TXN-R{self.account}-{next(self._txn)}"


@pytest.fixture
def ids() -> Ids:
    return Ids(next(_counter))


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def _score_body(ids: Ids, **overrides) -> dict:
    body = {
        "transaction_id": ids.txn(),
        "account_id": ids.account,
        "amount": "120.00",
        "channel": "CARD_PRESENT",
    }
    body.update(overrides)
    return body


def _open_case(client: TestClient, ids: Ids, *, decline: bool = False) -> dict:
    """Score a transaction that yields REVIEW (or DECLINE) and return the created case as JSON."""
    overrides = (
        {"counterparty_country": "KP", "channel": "ONLINE", "amount": "9800.00"}
        if decline
        else {"counterparty_country": "IR"}
    )
    body = _score_body(ids, **overrides)
    response = client.post("/api/v1/risk/score", json=body)
    assert response.status_code == 200
    listing = client.get("/api/v1/cases").json()
    matches = [c for c in listing if c["transaction_id"] == body["transaction_id"]]
    assert len(matches) == 1
    return matches[0]


# ---------------------------------------------------------------- health


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "fraud-detection-service"}


# ---------------------------------------------------------------- /risk/score


def test_score_approve_returns_response_and_opens_no_case(client, ids):
    body = _score_body(ids)
    response = client.post("/api/v1/risk/score", json=body)
    assert response.status_code == 200
    payload = response.json()
    assert payload["transaction_id"] == body["transaction_id"]
    assert payload["decision"] == "APPROVE"
    assert payload["score"] == 0
    assert payload["signals"] == []
    assert payload["model_version"] == "rules-2.1.0"
    assert all(c["transaction_id"] != body["transaction_id"] for c in client.get("/api/v1/cases").json())


def test_score_review_opens_case(client, ids):
    case = _open_case(client, ids)
    assert case["account_id"] == ids.account
    assert case["status"] == "OPEN"
    assert case["score"] >= 40


def test_score_decline_opens_case(client, ids):
    case = _open_case(client, ids, decline=True)
    assert case["score"] >= 75
    assert case["status"] == "OPEN"


def test_duplicate_transaction_returns_409(client, ids):
    body = _score_body(ids)
    assert client.post("/api/v1/risk/score", json=body).status_code == 200
    response = client.post("/api/v1/risk/score", json=body)
    assert response.status_code == 409
    detail = response.json()["detail"]
    assert detail["code"] == "DUPLICATE_TRANSACTION"
    assert body["transaction_id"] in detail["message"]


@pytest.mark.parametrize(
    "overrides",
    [
        {"amount": "0"},
        {"amount": "-5.00"},
        {"amount": "10.123"},
        {"channel": "CARRIER_PIGEON"},
        {"transaction_id": ""},
        {"currency": "USDX"},
        {"counterparty_country": "USA"},
    ],
)
def test_score_rejects_invalid_payloads(client, ids, overrides):
    response = client.post("/api/v1/risk/score", json=_score_body(ids, **overrides))
    assert response.status_code == 422


# ---------------------------------------------------------------- /risk/profile


def test_profile_reflects_scored_transactions(client, ids):
    client.post(
        "/api/v1/risk/score", json=_score_body(ids, amount="500.00", channel="MOBILE", device_id="DEV-9")
    )
    response = client.get(f"/api/v1/risk/profile/{ids.account}")
    assert response.status_code == 200
    assert response.json() == {
        "account_id": ids.account,
        "transaction_count": 1,
        "average_amount": "275.00",
        "known_devices": ["DEV-9"],
        "home_country": "US",
    }


def test_profile_for_unknown_account_returns_defaults(client):
    response = client.get("/api/v1/risk/profile/ACC-NEVER-SEEN")
    assert response.status_code == 200
    payload = response.json()
    assert payload["transaction_count"] == 0
    assert payload["average_amount"] == "250.00"
    assert payload["known_devices"] == []


# ---------------------------------------------------------------- /cases


def test_list_open_cases_includes_new_case(client, ids):
    case = _open_case(client, ids)
    listing = client.get("/api/v1/cases")
    assert listing.status_code == 200
    assert case["case_id"] in {c["case_id"] for c in listing.json()}


def test_get_case(client, ids):
    case = _open_case(client, ids)
    response = client.get(f"/api/v1/cases/{case['case_id']}")
    assert response.status_code == 200
    assert response.json() == case


def test_get_unknown_case_returns_404(client):
    response = client.get("/api/v1/cases/CASE-MISSING")
    assert response.status_code == 404
    assert response.json()["detail"] == {"code": "CASE_NOT_FOUND", "message": "Unknown case CASE-MISSING"}


def test_assign_case(client, ids):
    case = _open_case(client, ids)
    response = client.post(f"/api/v1/cases/{case['case_id']}/assign", params={"analyst": ANALYST})
    assert response.status_code == 200
    assert response.json()["assigned_to"] == ANALYST
    assert response.json()["status"] == "INVESTIGATING"


def test_assign_blank_analyst_returns_422(client, ids):
    case = _open_case(client, ids)
    response = client.post(f"/api/v1/cases/{case['case_id']}/assign", params={"analyst": "   "})
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "ANALYST_REQUIRED"


def test_assign_missing_analyst_query_returns_422(client, ids):
    case = _open_case(client, ids)
    assert client.post(f"/api/v1/cases/{case['case_id']}/assign").status_code == 422


def test_assign_closed_case_returns_409(client, ids):
    case = _open_case(client, ids)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})
    client.post(f"/api/v1/cases/{case_id}/close", params={"analyst": ANALYST, "confirmed_fraud": "false"})
    response = client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": "analyst.b"})
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "CASE_CLOSED"


def test_add_note(client, ids):
    case = _open_case(client, ids)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})
    response = client.post(
        f"/api/v1/cases/{case_id}/notes", params={"analyst": ANALYST, "note": "Customer confirmed travel"}
    )
    assert response.status_code == 200
    notes = response.json()["notes"]
    assert len(notes) == 1
    assert notes[0].endswith(f"{ANALYST}: Customer confirmed travel")


def test_add_note_too_short_returns_422(client, ids):
    case = _open_case(client, ids)
    response = client.post(
        f"/api/v1/cases/{case['case_id']}/notes", params={"analyst": ANALYST, "note": "ok"}
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "NOTE_TOO_SHORT"


def test_add_note_by_non_assignee_returns_403(client, ids):
    case = _open_case(client, ids)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})
    response = client.post(
        f"/api/v1/cases/{case_id}/notes", params={"analyst": "analyst.b", "note": "Not mine"}
    )
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "NOT_ASSIGNEE"


def test_add_note_unknown_case_returns_404(client):
    response = client.post(
        "/api/v1/cases/CASE-MISSING/notes", params={"analyst": ANALYST, "note": "Hello there"}
    )
    assert response.status_code == 404


def test_close_case_as_false_positive(client, ids):
    case = _open_case(client, ids)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})
    response = client.post(
        f"/api/v1/cases/{case_id}/close", params={"analyst": ANALYST, "confirmed_fraud": "false"}
    )
    assert response.status_code == 200
    assert response.json()["status"] == "FALSE_POSITIVE"
    assert response.json()["sar_filed"] is False
    assert case_id not in {c["case_id"] for c in client.get("/api/v1/cases").json()}


def test_close_not_investigating_returns_409(client, ids):
    case = _open_case(client, ids)
    response = client.post(
        f"/api/v1/cases/{case['case_id']}/close", params={"analyst": ANALYST, "confirmed_fraud": "false"}
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "CASE_NOT_INVESTIGATING"


def test_close_by_wrong_analyst_returns_403(client, ids):
    case = _open_case(client, ids)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})
    response = client.post(
        f"/api/v1/cases/{case_id}/close", params={"analyst": "analyst.b", "confirmed_fraud": "true"}
    )
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "NOT_ASSIGNEE"


def test_close_confirmed_fraud_without_sar_returns_422_then_succeeds_with_sar(client, ids):
    case = _open_case(client, ids, decline=True)
    case_id = case["case_id"]
    client.post(f"/api/v1/cases/{case_id}/assign", params={"analyst": ANALYST})

    denied = client.post(
        f"/api/v1/cases/{case_id}/close", params={"analyst": ANALYST, "confirmed_fraud": "true"}
    )
    assert denied.status_code == 422
    assert denied.json()["detail"]["code"] == "SAR_REQUIRED"

    closed = client.post(
        f"/api/v1/cases/{case_id}/close",
        params={"analyst": ANALYST, "confirmed_fraud": "true", "sar_filed": "true"},
    )
    assert closed.status_code == 200
    assert closed.json()["status"] == "CONFIRMED_FRAUD"
    assert closed.json()["sar_filed"] is True


def test_close_missing_confirmed_fraud_returns_422(client, ids):
    case = _open_case(client, ids)
    assert (
        client.post(f"/api/v1/cases/{case['case_id']}/close", params={"analyst": ANALYST}).status_code == 422
    )


def test_sar_overdue_lists_only_stale_high_score_cases(client, ids):
    stale = _open_case(client, ids, decline=True)
    fresh = _open_case(client, ids, decline=True)
    cases.get(stale["case_id"]).opened_at = datetime.now(timezone.utc) - SAR_DEADLINE - timedelta(days=1)

    response = client.get("/api/v1/cases/sar-overdue")
    assert response.status_code == 200
    overdue_ids = {c["case_id"] for c in response.json()}
    assert stale["case_id"] in overdue_ids
    assert fresh["case_id"] not in overdue_ids
