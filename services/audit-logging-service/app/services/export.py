"""Examiner export and SIEM forwarding.

Produces signed evidence packages (JSON Lines + manifest) for OCC / internal
audit requests, and forwards CRITICAL events to a simulated SIEM sink with
retry and back-off.

Coverage note: untested.
"""

import hashlib
import hmac
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone

from app.models.audit import AuditEvent, EventCategory, Severity
from app.services.trail import AuditError

_SIGNING_KEY = b"demo-export-signing-key-rotate-me"
MAX_EXPORT_EVENTS = 50_000
MAX_SIEM_ATTEMPTS = 5


@dataclass
class EvidencePackage:
    request_id: str
    requested_by: str
    generated_at: datetime
    event_count: int
    first_sequence: int | None
    last_sequence: int | None
    body: str
    body_sha256: str
    signature: str
    manifest: dict[str, object] = field(default_factory=dict)


class ExamExporter:
    def build(self, events: list[AuditEvent], request_id: str, requested_by: str, purpose: str) -> EvidencePackage:
        if not request_id or not request_id.strip():
            raise AuditError("EXPORT_REQUEST_ID_REQUIRED", "Examiner request id is required", 422)
        if not requested_by or "@" not in requested_by:
            raise AuditError("EXPORT_REQUESTER_INVALID", "requested_by must be an email address", 422)
        if purpose not in {"OCC_EXAM", "INTERNAL_AUDIT", "SOX_TESTING", "LITIGATION", "REGULATOR_INQUIRY"}:
            raise AuditError("EXPORT_PURPOSE_INVALID", f"Unsupported export purpose {purpose}", 422)
        if len(events) > MAX_EXPORT_EVENTS:
            raise AuditError("EXPORT_TOO_LARGE", "Export exceeds 50,000 events; narrow the query", 413)

        ordered = sorted(events, key=lambda e: e.sequence)
        for previous, current in zip(ordered, ordered[1:]):
            if current.previous_hash != previous.hash:
                raise AuditError("EXPORT_CHAIN_GAP", f"Chain gap before sequence {current.sequence}", 409)

        lines = [json.dumps(e.model_dump(mode="json"), sort_keys=True, separators=(",", ":")) for e in ordered]
        body = "\n".join(lines)
        digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
        signature = hmac.new(_SIGNING_KEY, digest.encode("utf-8"), hashlib.sha256).hexdigest()
        now = datetime.now(timezone.utc)
        manifest = {
            "request_id": request_id,
            "requested_by": requested_by,
            "purpose": purpose,
            "generated_at": now.isoformat(),
            "event_count": len(ordered),
            "categories": sorted({e.category.value for e in ordered}),
            "body_sha256": digest,
        }
        return EvidencePackage(
            request_id=request_id,
            requested_by=requested_by,
            generated_at=now,
            event_count=len(ordered),
            first_sequence=ordered[0].sequence if ordered else None,
            last_sequence=ordered[-1].sequence if ordered else None,
            body=body,
            body_sha256=digest,
            signature=signature,
            manifest=manifest,
        )

    @staticmethod
    def verify(package: EvidencePackage) -> bool:
        digest = hashlib.sha256(package.body.encode("utf-8")).hexdigest()
        if digest != package.body_sha256:
            return False
        expected = hmac.new(_SIGNING_KEY, digest.encode("utf-8"), hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, package.signature)


class SiemForwarder:
    def __init__(self) -> None:
        self.delivered: list[dict[str, object]] = []
        self.failed: list[tuple[AuditEvent, int]] = []
        self.outage = False
        self._attempts: dict[str, int] = {}

    def should_forward(self, event: AuditEvent) -> bool:
        if event.severity is Severity.CRITICAL:
            return True
        if event.category in {EventCategory.PII_ACCESS, EventCategory.ADMIN}:
            return True
        return event.outcome in {"DENIED", "ERROR"} and event.category is EventCategory.AUTHENTICATION

    def forward(self, event: AuditEvent) -> bool:
        if not self.should_forward(event):
            return False
        attempts = self._attempts.get(event.event_id, 0) + 1
        self._attempts[event.event_id] = attempts
        if self.outage:
            if attempts >= MAX_SIEM_ATTEMPTS:
                self.failed.append((event, attempts))
                raise AuditError("SIEM_DELIVERY_FAILED", f"Gave up after {attempts} attempts", 502)
            return False
        self.delivered.append(
            {
                "event_id": event.event_id,
                "sequence": event.sequence,
                "severity": event.severity.value,
                "category": event.category.value,
                "backoff_seconds": self.backoff_seconds(attempts),
            }
        )
        return True

    @staticmethod
    def backoff_seconds(attempt: int) -> int:
        return min(2 ** (attempt - 1), 60)


exporter = ExamExporter()
siem = SiemForwarder()
