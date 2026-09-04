"""Real-time anomaly rules over the audit stream.

Rules fire on patterns examiners specifically look for: repeated failed
logins, after-hours admin changes, bulk PII access, and privilege changes
without a change ticket.

Coverage note: untested.
"""

from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone

from app.models.audit import AuditEvent, EventCategory

FAILED_LOGIN_THRESHOLD = 5
FAILED_LOGIN_WINDOW = timedelta(minutes=10)
BULK_PII_THRESHOLD = 50
BULK_PII_WINDOW = timedelta(minutes=5)


class Alert:
    def __init__(self, rule: str, message: str, event: AuditEvent) -> None:
        self.rule = rule
        self.message = message
        self.sequence = event.sequence
        self.actor = event.actor
        self.raised_at = datetime.now(timezone.utc)

    def to_dict(self) -> dict[str, object]:
        return {
            "rule": self.rule,
            "message": self.message,
            "sequence": self.sequence,
            "actor": self.actor,
            "raised_at": self.raised_at.isoformat(),
        }


class AnomalyDetector:
    def __init__(self) -> None:
        self._failed_logins: dict[str, deque[datetime]] = defaultdict(deque)
        self._pii_reads: dict[str, deque[datetime]] = defaultdict(deque)
        self.alerts: list[Alert] = []

    def evaluate(self, event: AuditEvent) -> list[Alert]:
        raised: list[Alert] = []
        when = event.occurred_at or event.recorded_at
        actor = event.actor or "<unknown>"

        if event.category is EventCategory.AUTHENTICATION and event.outcome in {"FAILURE", "DENIED"}:
            window = self._failed_logins[actor]
            window.append(when)
            self._trim(window, when - FAILED_LOGIN_WINDOW)
            if len(window) >= FAILED_LOGIN_THRESHOLD:
                raised.append(Alert("REPEATED_FAILED_LOGIN", f"{len(window)} failed logins for {actor}", event))
                window.clear()

        if event.category is EventCategory.PII_ACCESS:
            window = self._pii_reads[actor]
            window.append(when)
            self._trim(window, when - BULK_PII_WINDOW)
            if len(window) >= BULK_PII_THRESHOLD:
                raised.append(Alert("BULK_PII_ACCESS", f"{actor} read {len(window)} PII records in 5 min", event))
                window.clear()

        if event.category in {EventCategory.ADMIN, EventCategory.CONFIGURATION}:
            hour = when.astimezone(timezone.utc).hour
            if hour < 6 or hour >= 22 or when.weekday() >= 5:
                raised.append(Alert("AFTER_HOURS_ADMIN", f"{event.action} by {actor} outside business hours", event))
            if "change_ticket" not in event.details and event.actor_type != "service":
                raised.append(Alert("ADMIN_WITHOUT_TICKET", f"{event.action} has no change ticket", event))

        if event.category is EventCategory.AUTHORIZATION and event.action.upper().startswith("GRANT_"):
            granted_to = str(event.details.get("grantee", ""))
            if granted_to and granted_to == event.actor:
                raised.append(Alert("SELF_PRIVILEGE_GRANT", f"{actor} granted privileges to themselves", event))

        self.alerts.extend(raised)
        return raised

    @staticmethod
    def _trim(window: deque[datetime], cutoff: datetime) -> None:
        while window and window[0] < cutoff:
            window.popleft()

    def recent(self, limit: int = 50) -> list[dict[str, object]]:
        return [a.to_dict() for a in self.alerts[-limit:]]


detector = AnomalyDetector()
