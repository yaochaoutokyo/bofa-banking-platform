"""Rule-based risk scoring engine.

Each rule contributes a weighted signal; the sum (capped at 100) maps to an
APPROVE / REVIEW / DECLINE decision. Account history is kept in memory.
"""

from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from app.models.risk import Channel, Decision, RiskSignal, ScoreRequest, ScoreResponse

MODEL_VERSION = "rules-2.1.0"
REVIEW_THRESHOLD = 40
DECLINE_THRESHOLD = 75

HIGH_RISK_COUNTRIES = {"KP", "IR", "SY", "CU", "MM", "RU", "BY", "VE"}
HIGH_RISK_MCC = {"7995", "6051", "4829", "5967", "7273"}  # gambling, quasi-cash, wires, direct marketing, dating
STRUCTURING_FLOOR = Decimal("9000.00")
STRUCTURING_CEILING = Decimal("10000.00")
VELOCITY_WINDOW = timedelta(hours=1)
VELOCITY_LIMIT = 6


class AccountProfile:
    def __init__(self) -> None:
        self.recent: deque[tuple[datetime, Decimal]] = deque()
        self.known_devices: set[str] = set()
        self.home_country = "US"
        self.average_amount = Decimal("250.00")
        self.transaction_count = 0

    def observe(self, when: datetime, amount: Decimal, device_id: str | None) -> None:
        self.recent.append((when, amount))
        cutoff = when - timedelta(days=30)
        while self.recent and self.recent[0][0] < cutoff:
            self.recent.popleft()
        if device_id:
            self.known_devices.add(device_id)
        self.transaction_count += 1
        # exponential moving average
        self.average_amount = (self.average_amount * 9 + amount) / 10

    def count_within(self, when: datetime, window: timedelta) -> int:
        cutoff = when - window
        return sum(1 for ts, _ in self.recent if ts >= cutoff)

    def sum_within(self, when: datetime, window: timedelta) -> Decimal:
        cutoff = when - window
        return sum((amt for ts, amt in self.recent if ts >= cutoff), Decimal("0"))


class ScoringEngine:
    def __init__(self) -> None:
        self._profiles: dict[str, AccountProfile] = defaultdict(AccountProfile)
        self._seen: set[str] = set()

    def profile(self, account_id: str) -> AccountProfile:
        return self._profiles[account_id]

    def score(self, request: ScoreRequest) -> ScoreResponse:
        if request.transaction_id in self._seen:
            raise ValueError(f"Transaction {request.transaction_id} already scored")
        when = request.occurred_at or datetime.now(timezone.utc)
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        profile = self._profiles[request.account_id]
        signals: list[RiskSignal] = []

        signals.extend(self._amount_rules(request, profile))
        signals.extend(self._geo_rules(request, profile))
        signals.extend(self._velocity_rules(request, profile, when))
        signals.extend(self._device_rules(request, profile))
        signals.extend(self._channel_rules(request))

        total = min(sum(s.weight for s in signals), 100)
        decision = self._decide(total)

        profile.observe(when, request.amount, request.device_id)
        self._seen.add(request.transaction_id)
        return ScoreResponse(
            transaction_id=request.transaction_id,
            score=total,
            decision=decision,
            signals=signals,
            model_version=MODEL_VERSION,
        )

    @staticmethod
    def _decide(score: int) -> Decision:
        if score >= DECLINE_THRESHOLD:
            return Decision.DECLINE
        if score >= REVIEW_THRESHOLD:
            return Decision.REVIEW
        return Decision.APPROVE

    def _amount_rules(self, request: ScoreRequest, profile: AccountProfile) -> list[RiskSignal]:
        signals: list[RiskSignal] = []
        if profile.transaction_count >= 5 and request.amount > profile.average_amount * 10:
            signals.append(RiskSignal(code="AMOUNT_10X_AVERAGE", weight=30, detail="Amount exceeds 10x account average"))
        elif profile.transaction_count >= 5 and request.amount > profile.average_amount * 4:
            signals.append(RiskSignal(code="AMOUNT_4X_AVERAGE", weight=15, detail="Amount exceeds 4x account average"))
        if STRUCTURING_FLOOR <= request.amount < STRUCTURING_CEILING:
            signals.append(RiskSignal(code="STRUCTURING_BAND", weight=25, detail="Amount just below CTR threshold"))
        if request.amount >= Decimal("50000.00") and request.channel is not Channel.WIRE:
            signals.append(RiskSignal(code="LARGE_NON_WIRE", weight=20, detail="Large amount on non-wire channel"))
        if request.amount == request.amount.to_integral_value() and request.amount >= Decimal("1000"):
            signals.append(RiskSignal(code="ROUND_AMOUNT", weight=5, detail="Round-figure amount"))
        return signals

    def _geo_rules(self, request: ScoreRequest, profile: AccountProfile) -> list[RiskSignal]:
        signals: list[RiskSignal] = []
        if request.counterparty_country.upper() in HIGH_RISK_COUNTRIES:
            signals.append(RiskSignal(code="HIGH_RISK_COUNTRY", weight=45, detail="Counterparty in high-risk jurisdiction"))
        elif request.counterparty_country.upper() != profile.home_country:
            signals.append(RiskSignal(code="CROSS_BORDER", weight=10, detail="Cross-border counterparty"))
        if request.ip_country and request.ip_country.upper() != profile.home_country:
            if request.channel in {Channel.ONLINE, Channel.MOBILE}:
                signals.append(RiskSignal(code="FOREIGN_IP", weight=15, detail="Digital session from foreign IP"))
        return signals

    def _velocity_rules(self, request: ScoreRequest, profile: AccountProfile, when: datetime) -> list[RiskSignal]:
        signals: list[RiskSignal] = []
        count = profile.count_within(when, VELOCITY_WINDOW)
        if count >= VELOCITY_LIMIT:
            signals.append(RiskSignal(code="VELOCITY", weight=30, detail=f"{count} transactions in the last hour"))
        elif count >= VELOCITY_LIMIT // 2:
            signals.append(RiskSignal(code="VELOCITY_ELEVATED", weight=10, detail=f"{count} transactions in the last hour"))
        daily = profile.sum_within(when, timedelta(hours=24)) + request.amount
        if daily >= Decimal("10000.00") and request.amount < STRUCTURING_CEILING:
            band_count = sum(
                1 for ts, amt in profile.recent if ts >= when - timedelta(hours=24) and STRUCTURING_FLOOR <= amt
            )
            if band_count >= 1:
                signals.append(RiskSignal(code="STRUCTURING_PATTERN", weight=35, detail="Multiple sub-threshold amounts"))
        return signals

    def _device_rules(self, request: ScoreRequest, profile: AccountProfile) -> list[RiskSignal]:
        signals: list[RiskSignal] = []
        if request.channel in {Channel.ONLINE, Channel.MOBILE}:
            if not request.device_id:
                signals.append(RiskSignal(code="DEVICE_MISSING", weight=20, detail="Digital channel without device id"))
            elif profile.known_devices and request.device_id not in profile.known_devices:
                signals.append(RiskSignal(code="NEW_DEVICE", weight=15, detail="Unrecognised device"))
        return signals

    def _channel_rules(self, request: ScoreRequest) -> list[RiskSignal]:
        signals: list[RiskSignal] = []
        if request.merchant_category and request.merchant_category in HIGH_RISK_MCC:
            signals.append(RiskSignal(code="HIGH_RISK_MCC", weight=15, detail="High-risk merchant category"))
        if request.channel is Channel.CARD_NOT_PRESENT and request.amount > Decimal("2500.00"):
            signals.append(RiskSignal(code="CNP_LARGE", weight=10, detail="Large card-not-present purchase"))
        if request.channel is Channel.ATM and request.amount > Decimal("1000.00"):
            signals.append(RiskSignal(code="ATM_LARGE", weight=10, detail="ATM withdrawal above daily norm"))
        return signals


engine = ScoringEngine()
