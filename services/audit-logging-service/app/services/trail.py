"""Append-only, hash-chained audit trail.

Each event's ``hash`` covers its canonical JSON plus the previous event's
hash, so any mutation or deletion breaks the chain and is detectable by
``verify_chain``.

Coverage note: the only tested path is a single successful append. Write
failures (storage full / sink unavailable), ordering under concurrent
appends, immutability enforcement, missing actor/timestamp handling and
redaction of sensitive details are all unverified.
"""

import hashlib
import json
import uuid
from datetime import datetime, timedelta, timezone
from threading import Lock

from app.models.audit import AuditEvent, AuditEventIn, EventCategory, IntegrityReport, QueryFilter, Severity
from app.services import redaction

GENESIS_HASH = "0" * 64
MAX_CLOCK_SKEW = timedelta(minutes=5)
MAX_DETAILS_BYTES = 16 * 1024


class AuditError(Exception):
    def __init__(self, code: str, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class StorageSink:
    """Simulated durable sink. Capacity and availability can be toggled to
    exercise write-failure paths."""

    def __init__(self, capacity: int = 100_000) -> None:
        self.capacity = capacity
        self.available = True
        self.records: list[AuditEvent] = []

    def append(self, event: AuditEvent) -> None:
        if not self.available:
            raise AuditError("SINK_UNAVAILABLE", "Audit sink is unavailable", status=503)
        if len(self.records) >= self.capacity:
            raise AuditError("SINK_FULL", "Audit sink capacity exhausted", status=507)
        self.records.append(event)


class AuditTrail:
    def __init__(self, sink: StorageSink | None = None) -> None:
        self._sink = sink or StorageSink()
        self._lock = Lock()
        self._head_hash = GENESIS_HASH
        self._sequence = 0
        self._dead_letter: list[tuple[AuditEventIn, str]] = []
        self._require_actor_for = {
            EventCategory.TRANSACTION,
            EventCategory.PII_ACCESS,
            EventCategory.CONFIGURATION,
            EventCategory.ADMIN,
        }

    # -- write path -------------------------------------------------------

    def append(self, incoming: AuditEventIn) -> AuditEvent:
        now = datetime.now(timezone.utc)
        self._validate(incoming, now)

        details = redaction.redact(incoming.details)
        if len(json.dumps(details, default=str)) > MAX_DETAILS_BYTES:
            raise AuditError("DETAILS_TOO_LARGE", "details exceeds 16 KiB after redaction", status=413)

        with self._lock:
            sequence = self._sequence + 1
            occurred_at = incoming.occurred_at or now
            event = AuditEvent(
                **incoming.model_dump(exclude={"details", "occurred_at"}),
                details=details,
                occurred_at=occurred_at,
                sequence=sequence,
                event_id=str(uuid.uuid4()),
                recorded_at=now,
                previous_hash=self._head_hash,
                hash="",
            )
            event.hash = self._compute_hash(event)
            try:
                self._sink.append(event)
            except AuditError as exc:
                self._dead_letter.append((incoming, exc.code))
                raise
            self._sequence = sequence
            self._head_hash = event.hash
            return event

    def _validate(self, incoming: AuditEventIn, now: datetime) -> None:
        if incoming.category in self._require_actor_for and not (incoming.actor and incoming.actor.strip()):
            raise AuditError("ACTOR_REQUIRED", f"{incoming.category.value} events must carry an actor", status=422)
        if incoming.actor is not None and incoming.actor.strip().lower() in {"system", "unknown", "anonymous", "-"}:
            if incoming.actor_type != "service":
                raise AuditError("ACTOR_PLACEHOLDER", "Placeholder actors are not permitted for users", status=422)
        if incoming.occurred_at is not None:
            if incoming.occurred_at.tzinfo is None:
                raise AuditError("TIMESTAMP_NAIVE", "occurred_at must be timezone-aware", status=422)
            if incoming.occurred_at > now + MAX_CLOCK_SKEW:
                raise AuditError("TIMESTAMP_FUTURE", "occurred_at is too far in the future", status=422)
            if incoming.occurred_at < now - timedelta(days=7):
                raise AuditError("TIMESTAMP_STALE", "occurred_at is more than 7 days old", status=422)
        if incoming.outcome not in {"SUCCESS", "FAILURE", "DENIED", "ERROR"}:
            raise AuditError("OUTCOME_INVALID", "outcome must be SUCCESS, FAILURE, DENIED or ERROR", status=422)
        if incoming.severity is Severity.CRITICAL and not incoming.correlation_id:
            raise AuditError("CORRELATION_REQUIRED", "CRITICAL events must carry a correlation_id", status=422)

    @staticmethod
    def _compute_hash(event: AuditEvent) -> str:
        canonical = json.dumps(
            event.model_dump(exclude={"hash"}, mode="json"),
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    # -- read path --------------------------------------------------------

    def get(self, sequence: int) -> AuditEvent:
        if sequence < 1 or sequence > len(self._sink.records):
            raise AuditError("EVENT_NOT_FOUND", f"No event with sequence {sequence}", status=404)
        return self._sink.records[sequence - 1]

    def query(self, flt: QueryFilter) -> list[AuditEvent]:
        result = self._sink.records
        if flt.category:
            result = [e for e in result if e.category == flt.category]
        if flt.actor:
            result = [e for e in result if e.actor == flt.actor]
        if flt.subject:
            result = [e for e in result if e.subject == flt.subject]
        if flt.since:
            result = [e for e in result if e.occurred_at and e.occurred_at >= flt.since]
        if flt.until:
            result = [e for e in result if e.occurred_at and e.occurred_at <= flt.until]
        return list(result[-flt.limit :])

    def count(self) -> int:
        return len(self._sink.records)

    def head_hash(self) -> str:
        return self._head_hash

    # -- integrity --------------------------------------------------------

    def verify_chain(self) -> IntegrityReport:
        previous = GENESIS_HASH
        for event in self._sink.records:
            if event.previous_hash != previous:
                return IntegrityReport(
                    total_events=len(self._sink.records),
                    verified=False,
                    first_bad_sequence=event.sequence,
                    head_hash=self._head_hash,
                )
            recomputed = self._compute_hash(event)
            if recomputed != event.hash:
                return IntegrityReport(
                    total_events=len(self._sink.records),
                    verified=False,
                    first_bad_sequence=event.sequence,
                    head_hash=self._head_hash,
                )
            previous = event.hash
        return IntegrityReport(
            total_events=len(self._sink.records),
            verified=previous == self._head_hash,
            first_bad_sequence=None,
            head_hash=self._head_hash,
        )

    def dead_letters(self) -> list[dict[str, str]]:
        return [{"action": e.action, "source_service": e.source_service, "reason": code} for e, code in self._dead_letter]

    def replay_dead_letters(self) -> int:
        replayed = 0
        pending = list(self._dead_letter)
        self._dead_letter.clear()
        for incoming, _ in pending:
            try:
                self.append(incoming)
                replayed += 1
            except AuditError:
                continue
        return replayed

    @property
    def sink(self) -> StorageSink:
        return self._sink


trail = AuditTrail()
