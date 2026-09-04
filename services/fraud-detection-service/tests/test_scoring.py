from decimal import Decimal

import pytest

from app.models.risk import Channel, Decision, ScoreRequest
from app.services.scoring import ScoringEngine


@pytest.fixture
def engine() -> ScoringEngine:
    return ScoringEngine()


def _request(**overrides) -> ScoreRequest:
    base = {
        "transaction_id": "TXN-1",
        "account_id": "ACC-1001",
        "amount": Decimal("120.00"),
        "channel": Channel.CARD_PRESENT,
    }
    base.update(overrides)
    return ScoreRequest(**base)


def test_low_risk_domestic_purchase_is_approved(engine):
    result = engine.score(_request())
    assert result.decision is Decision.APPROVE
    assert result.score == 0
    assert result.signals == []


def test_high_risk_country_triggers_review(engine):
    result = engine.score(_request(counterparty_country="IR"))
    assert result.decision is Decision.REVIEW
    assert any(s.code == "HIGH_RISK_COUNTRY" for s in result.signals)


def test_structuring_band_amount_adds_signal(engine):
    result = engine.score(_request(amount=Decimal("9500.00")))
    codes = {s.code for s in result.signals}
    assert "STRUCTURING_BAND" in codes


def test_digital_channel_without_device_is_flagged(engine):
    result = engine.score(_request(channel=Channel.ONLINE))
    assert any(s.code == "DEVICE_MISSING" for s in result.signals)


def test_combined_signals_can_decline(engine):
    result = engine.score(
        _request(amount=Decimal("9800.00"), counterparty_country="KP", channel=Channel.ONLINE, device_id=None)
    )
    assert result.decision is Decision.DECLINE
    assert result.score >= 75


def test_duplicate_transaction_id_rejected(engine):
    engine.score(_request())
    with pytest.raises(ValueError):
        engine.score(_request())
