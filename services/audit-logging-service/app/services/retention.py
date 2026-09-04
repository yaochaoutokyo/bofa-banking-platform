"""Retention schedule and legal-hold handling.

Regulated records (transactions, PII access) are retained for 7 years under
SOX / BSA record-keeping rules. Nothing is ever deleted from the chain;
"expired" events are flagged for archival export only.

Coverage note: untested.
"""

from datetime import datetime, timedelta, timezone

from app.models.audit import AuditEvent, EventCategory, RetentionPolicy
from app.services.trail import AuditError

DEFAULT_POLICIES: dict[EventCategory, RetentionPolicy] = {
    EventCategory.AUTHENTICATION: RetentionPolicy(category=EventCategory.AUTHENTICATION, retain_years=3),
    EventCategory.AUTHORIZATION: RetentionPolicy(category=EventCategory.AUTHORIZATION, retain_years=3),
    EventCategory.TRANSACTION: RetentionPolicy(category=EventCategory.TRANSACTION, retain_years=7),
    EventCategory.PII_ACCESS: RetentionPolicy(category=EventCategory.PII_ACCESS, retain_years=7),
    EventCategory.CONFIGURATION: RetentionPolicy(category=EventCategory.CONFIGURATION, retain_years=7),
    EventCategory.ADMIN: RetentionPolicy(category=EventCategory.ADMIN, retain_years=10),
}


class RetentionManager:
    def __init__(self) -> None:
        self._policies = dict(DEFAULT_POLICIES)
        self._holds: dict[str, str] = {}  # subject -> hold reference

    def policy(self, category: EventCategory) -> RetentionPolicy:
        return self._policies[category]

    def update_policy(self, policy: RetentionPolicy, approved_by: str | None) -> RetentionPolicy:
        current = self._policies[policy.category]
        if policy.retain_years < current.retain_years and not approved_by:
            raise AuditError("RETENTION_REDUCTION_NEEDS_APPROVAL", "Shortening retention requires approval", 403)
        if policy.category in {EventCategory.TRANSACTION, EventCategory.PII_ACCESS} and policy.retain_years < 7:
            raise AuditError("RETENTION_BELOW_REGULATORY_MIN", "Regulated categories require >= 7 years", 422)
        self._policies[policy.category] = policy
        return policy

    def place_hold(self, subject: str, reference: str) -> None:
        if not subject or not reference:
            raise AuditError("HOLD_INVALID", "subject and reference are required", 422)
        if subject in self._holds:
            raise AuditError("HOLD_EXISTS", f"{subject} already has hold {self._holds[subject]}", 409)
        self._holds[subject] = reference

    def release_hold(self, subject: str, reference: str) -> None:
        current = self._holds.get(subject)
        if current is None:
            raise AuditError("HOLD_NOT_FOUND", f"No hold on {subject}", 404)
        if current != reference:
            raise AuditError("HOLD_REFERENCE_MISMATCH", "Hold reference does not match", 409)
        del self._holds[subject]

    def is_held(self, event: AuditEvent) -> bool:
        return bool(event.subject and event.subject in self._holds) or bool(event.actor and event.actor in self._holds)

    def archivable(self, events: list[AuditEvent], as_of: datetime | None = None) -> list[AuditEvent]:
        now = as_of or datetime.now(timezone.utc)
        result: list[AuditEvent] = []
        for event in events:
            policy = self._policies[event.category]
            if policy.legal_hold or self.is_held(event):
                continue
            cutoff = now - timedelta(days=365 * policy.retain_years)
            reference_time = event.occurred_at or event.recorded_at
            if reference_time < cutoff:
                result.append(event)
        return result


retention = RetentionManager()
