"""Rule-level scoring tests that need multi-transaction history or specific channels."""

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from app.models.risk import Channel, Decision, ScoreRequest
from app.services.scoring import (
    MODEL_VERSION,
    VELOCITY_LIMIT,
    AccountProfile,
    ScoringEngine,
)

T0 = datetime(2024, 6, 1, 12, 0, tzinfo=timezone.utc)
ACCOUNT = "ACC-2001"


@pytest.fixture
def engine() -> ScoringEngine:
    return ScoringEngine()


def _request(transaction_id: str = "TXN-1", **overrides) -> ScoreRequest:
    base = {
        "transaction_id": transaction_id,
        "account_id": ACCOUNT,
        "amount": Decimal("120.00"),
        "channel": Channel.CARD_PRESENT,
        "occurred_at": T0,
    }
    base.update(overrides)
    return ScoreRequest(**base)


def _codes(result) -> set[str]:
    return {s.code for s in result.signals}


def _seed(
    engine: ScoringEngine, count: int, amount: Decimal, *, start: datetime = T0, step: timedelta, **overrides
):
    """Score `count` benign-ish transactions spaced by `step` and return the timestamp after the last."""
    when = start
    for i in range(count):
        engine.score(_request(f"SEED-{i}", amount=amount, occurred_at=when, **overrides))
        when += step
    return when


# ---------------------------------------------------------------- velocity


def test_velocity_limit_reached_triggers_velocity(engine):
    when = _seed(engine, VELOCITY_LIMIT, Decimal("120.00"), step=timedelta(minutes=5))
    result = engine.score(_request("TXN-V", occurred_at=when))
    velocity = [s for s in result.signals if s.code == "VELOCITY"]
    assert len(velocity) == 1
    assert velocity[0].weight == 30
    assert velocity[0].detail == f"{VELOCITY_LIMIT} transactions in the last hour"
    assert "VELOCITY_ELEVATED" not in _codes(result)


def test_half_velocity_limit_triggers_velocity_elevated(engine):
    when = _seed(engine, VELOCITY_LIMIT // 2, Decimal("120.00"), step=timedelta(minutes=5))
    result = engine.score(_request("TXN-V", occurred_at=when))
    assert "VELOCITY_ELEVATED" in _codes(result)
    assert "VELOCITY" not in _codes(result)
    assert result.decision is Decision.APPROVE


def test_transactions_outside_velocity_window_are_ignored(engine):
    when = _seed(engine, VELOCITY_LIMIT, Decimal("120.00"), step=timedelta(minutes=5))
    result = engine.score(_request("TXN-V", occurred_at=when + timedelta(hours=2)))
    assert "VELOCITY" not in _codes(result)
    assert "VELOCITY_ELEVATED" not in _codes(result)


def test_below_half_velocity_limit_has_no_velocity_signal(engine):
    when = _seed(engine, VELOCITY_LIMIT // 2 - 1, Decimal("120.00"), step=timedelta(minutes=5))
    result = engine.score(_request("TXN-V", occurred_at=when))
    assert not _codes(result) & {"VELOCITY", "VELOCITY_ELEVATED"}


# ---------------------------------------------------------------- structuring pattern


def test_structuring_pattern_when_daily_sum_crosses_ctr_threshold(engine):
    engine.score(_request("TXN-A", amount=Decimal("9500.00"), occurred_at=T0))
    result = engine.score(_request("TXN-B", amount=Decimal("900.00"), occurred_at=T0 + timedelta(hours=3)))
    pattern = [s for s in result.signals if s.code == "STRUCTURING_PATTERN"]
    assert len(pattern) == 1
    assert pattern[0].weight == 35
    assert "STRUCTURING_BAND" not in _codes(result)


def test_structuring_pattern_requires_prior_band_amount(engine):
    engine.score(_request("TXN-A", amount=Decimal("6000.00"), occurred_at=T0))
    result = engine.score(_request("TXN-B", amount=Decimal("5000.00"), occurred_at=T0 + timedelta(hours=1)))
    assert "STRUCTURING_PATTERN" not in _codes(result)


def test_structuring_pattern_not_applied_to_reportable_amount(engine):
    engine.score(_request("TXN-A", amount=Decimal("9500.00"), occurred_at=T0))
    result = engine.score(_request("TXN-B", amount=Decimal("12000.00"), occurred_at=T0 + timedelta(hours=1)))
    assert "STRUCTURING_PATTERN" not in _codes(result)


def test_structuring_pattern_ignores_transactions_older_than_a_day(engine):
    engine.score(_request("TXN-A", amount=Decimal("9500.00"), occurred_at=T0))
    result = engine.score(_request("TXN-B", amount=Decimal("900.00"), occurred_at=T0 + timedelta(hours=25)))
    assert "STRUCTURING_PATTERN" not in _codes(result)


# ---------------------------------------------------------------- amount rules


def test_amount_4x_average_after_history_established(engine):
    when = _seed(engine, 5, Decimal("100.00"), step=timedelta(days=1))
    profile = engine.profile(ACCOUNT)
    assert profile.transaction_count == 5
    amount = (profile.average_amount * 4 + Decimal("1")).quantize(Decimal("0.01"))
    result = engine.score(_request("TXN-BIG", amount=amount, occurred_at=when))
    assert "AMOUNT_4X_AVERAGE" in _codes(result)
    assert "AMOUNT_10X_AVERAGE" not in _codes(result)


def test_amount_10x_average_after_history_established(engine):
    when = _seed(engine, 5, Decimal("100.00"), step=timedelta(days=1))
    profile = engine.profile(ACCOUNT)
    amount = (profile.average_amount * 10 + Decimal("1")).quantize(Decimal("0.01"))
    result = engine.score(_request("TXN-BIG", amount=amount, occurred_at=when))
    ten_x = [s for s in result.signals if s.code == "AMOUNT_10X_AVERAGE"]
    assert len(ten_x) == 1
    assert ten_x[0].weight == 30
    assert "AMOUNT_4X_AVERAGE" not in _codes(result)


def test_amount_rules_need_five_transactions_of_history(engine):
    when = _seed(engine, 4, Decimal("100.00"), step=timedelta(days=1))
    result = engine.score(_request("TXN-BIG", amount=Decimal("5000.50"), occurred_at=when))
    assert not _codes(result) & {"AMOUNT_4X_AVERAGE", "AMOUNT_10X_AVERAGE"}


def test_large_non_wire_amount_is_flagged(engine):
    result = engine.score(_request(amount=Decimal("50000.00"), channel=Channel.BRANCH))
    assert {"LARGE_NON_WIRE", "ROUND_AMOUNT"} <= _codes(result)


def test_large_wire_is_not_flagged_as_non_wire(engine):
    result = engine.score(_request(amount=Decimal("50000.00"), channel=Channel.WIRE))
    assert "LARGE_NON_WIRE" not in _codes(result)


@pytest.mark.parametrize(
    "amount,expected",
    [
        (Decimal("1000.00"), True),
        (Decimal("2500"), True),
        (Decimal("999.00"), False),
        (Decimal("1000.50"), False),
    ],
)
def test_round_amount_rule(engine, amount, expected):
    result = engine.score(_request(amount=amount))
    assert ("ROUND_AMOUNT" in _codes(result)) is expected


# ---------------------------------------------------------------- geo rules


def test_cross_border_counterparty_is_flagged(engine):
    result = engine.score(_request(counterparty_country="GB"))
    assert _codes(result) == {"CROSS_BORDER"}
    assert result.score == 10


def test_high_risk_country_is_case_insensitive_and_supersedes_cross_border(engine):
    result = engine.score(_request(counterparty_country="ru"))
    assert "HIGH_RISK_COUNTRY" in _codes(result)
    assert "CROSS_BORDER" not in _codes(result)


@pytest.mark.parametrize("channel", [Channel.ONLINE, Channel.MOBILE])
def test_foreign_ip_on_digital_channel_is_flagged(engine, channel):
    result = engine.score(_request(channel=channel, device_id="DEV-1", ip_country="FR"))
    assert "FOREIGN_IP" in _codes(result)


def test_foreign_ip_on_non_digital_channel_is_ignored(engine):
    result = engine.score(_request(channel=Channel.CARD_PRESENT, ip_country="FR"))
    assert "FOREIGN_IP" not in _codes(result)


def test_domestic_ip_is_not_flagged(engine):
    result = engine.score(_request(channel=Channel.ONLINE, device_id="DEV-1", ip_country="us"))
    assert "FOREIGN_IP" not in _codes(result)


# ---------------------------------------------------------------- device rules


def test_first_device_is_not_flagged(engine):
    result = engine.score(_request(channel=Channel.MOBILE, device_id="DEV-1"))
    assert result.signals == []
    assert engine.profile(ACCOUNT).known_devices == {"DEV-1"}


def test_new_device_after_known_device_is_flagged(engine):
    engine.score(_request("TXN-1", channel=Channel.MOBILE, device_id="DEV-1"))
    result = engine.score(_request("TXN-2", channel=Channel.MOBILE, device_id="DEV-2"))
    assert "NEW_DEVICE" in _codes(result)
    assert engine.profile(ACCOUNT).known_devices == {"DEV-1", "DEV-2"}


def test_known_device_is_not_flagged(engine):
    engine.score(_request("TXN-1", channel=Channel.MOBILE, device_id="DEV-1"))
    result = engine.score(_request("TXN-2", channel=Channel.ONLINE, device_id="DEV-1"))
    assert "NEW_DEVICE" not in _codes(result)


def test_device_rules_ignore_non_digital_channels(engine):
    result = engine.score(_request(channel=Channel.ATM))
    assert not _codes(result) & {"DEVICE_MISSING", "NEW_DEVICE"}


# ---------------------------------------------------------------- channel rules


@pytest.mark.parametrize("mcc", ["7995", "6051", "4829", "5967", "7273"])
def test_high_risk_mcc_is_flagged(engine, mcc):
    result = engine.score(_request(merchant_category=mcc))
    assert _codes(result) == {"HIGH_RISK_MCC"}


def test_ordinary_mcc_is_not_flagged(engine):
    result = engine.score(_request(merchant_category="5411"))
    assert "HIGH_RISK_MCC" not in _codes(result)


@pytest.mark.parametrize(
    "amount,expected",
    [(Decimal("2500.01"), True), (Decimal("2500.00"), False)],
)
def test_cnp_large_threshold(engine, amount, expected):
    result = engine.score(_request(channel=Channel.CARD_NOT_PRESENT, amount=amount))
    assert ("CNP_LARGE" in _codes(result)) is expected


@pytest.mark.parametrize(
    "amount,expected",
    [(Decimal("1000.01"), True), (Decimal("1000.00"), False)],
)
def test_atm_large_threshold(engine, amount, expected):
    result = engine.score(_request(channel=Channel.ATM, amount=amount))
    assert ("ATM_LARGE" in _codes(result)) is expected


# ---------------------------------------------------------------- decision / response


@pytest.mark.parametrize(
    "score,decision",
    [
        (0, Decision.APPROVE),
        (39, Decision.APPROVE),
        (40, Decision.REVIEW),
        (74, Decision.REVIEW),
        (75, Decision.DECLINE),
        (100, Decision.DECLINE),
    ],
)
def test_decision_thresholds(score, decision):
    assert ScoringEngine._decide(score) is decision


def test_score_is_capped_at_100(engine):
    engine.score(_request("TXN-A", amount=Decimal("9500.00"), occurred_at=T0))
    result = engine.score(
        _request(
            "TXN-B",
            amount=Decimal("9600.00"),
            counterparty_country="KP",
            channel=Channel.ONLINE,
            ip_country="KP",
            occurred_at=T0 + timedelta(minutes=1),
        )
    )
    assert sum(s.weight for s in result.signals) > 100
    assert result.score == 100
    assert result.decision is Decision.DECLINE
    assert result.model_version == MODEL_VERSION


def test_naive_occurred_at_is_treated_as_utc(engine):
    naive = datetime(2024, 6, 1, 12, 0)
    engine.score(_request("TXN-A", occurred_at=naive))
    (observed, _) = engine.profile(ACCOUNT).recent[0]
    assert observed == T0


# ---------------------------------------------------------------- profile


def test_profile_updates_after_scoring(engine):
    engine.score(_request("TXN-1", amount=Decimal("500.00"), channel=Channel.MOBILE, device_id="DEV-1"))
    profile = engine.profile(ACCOUNT)
    assert profile.transaction_count == 1
    assert profile.average_amount == Decimal("275.00")
    assert profile.known_devices == {"DEV-1"}
    assert profile.home_country == "US"
    assert engine.profile(ACCOUNT) is profile


def test_profile_for_unknown_account_has_defaults(engine):
    profile = engine.profile("ACC-NEW")
    assert profile.transaction_count == 0
    assert profile.average_amount == Decimal("250.00")
    assert profile.known_devices == set()


def test_observe_moving_average_and_device_tracking():
    profile = AccountProfile()
    profile.observe(T0, Decimal("1000.00"), "DEV-1")
    assert profile.average_amount == Decimal("325.00")
    profile.observe(T0 + timedelta(minutes=1), Decimal("325.00"), None)
    assert profile.average_amount == Decimal("325.00")
    assert profile.transaction_count == 2
    assert profile.known_devices == {"DEV-1"}


def test_observe_prunes_history_older_than_thirty_days():
    profile = AccountProfile()
    profile.observe(T0, Decimal("10.00"), None)
    profile.observe(T0 + timedelta(days=10), Decimal("20.00"), None)
    profile.observe(T0 + timedelta(days=31), Decimal("30.00"), None)
    assert [amt for _, amt in profile.recent] == [Decimal("20.00"), Decimal("30.00")]
    assert profile.transaction_count == 3


def test_count_and_sum_within_window():
    profile = AccountProfile()
    profile.observe(T0, Decimal("100.00"), None)
    profile.observe(T0 + timedelta(minutes=30), Decimal("200.00"), None)
    profile.observe(T0 + timedelta(minutes=90), Decimal("300.00"), None)
    now = T0 + timedelta(minutes=90)
    assert profile.count_within(now, timedelta(hours=1)) == 2
    assert profile.sum_within(now, timedelta(hours=1)) == Decimal("500.00")
    assert profile.sum_within(now, timedelta(minutes=1)) == Decimal("300.00")
    assert AccountProfile().sum_within(now, timedelta(hours=1)) == Decimal("0")
