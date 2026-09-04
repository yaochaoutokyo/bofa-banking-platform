"""Chooses a channel, honours preferences and quiet hours, deduplicates, and
records deliveries to an in-memory outbox."""

import uuid
from datetime import datetime, timedelta, timezone

from app.models.notification import Channel, Delivery, DeliveryStatus, Preferences, Priority, SendRequest
from app.services import templates

DEDUPE_WINDOW = timedelta(hours=6)


class DispatchError(Exception):
    def __init__(self, code: str, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class PreferenceStore:
    def __init__(self) -> None:
        self._prefs: dict[str, Preferences] = {}

    def get(self, customer_id: str) -> Preferences:
        prefs = self._prefs.get(customer_id)
        if prefs is None:
            raise DispatchError("PREFERENCES_NOT_FOUND", f"No preferences for {customer_id}", 404)
        return prefs

    def upsert(self, prefs: Preferences) -> Preferences:
        if Channel.EMAIL in prefs.channels and not prefs.email:
            raise DispatchError("EMAIL_REQUIRED", "EMAIL channel selected but no email on file", 422)
        if Channel.SMS in prefs.channels and not prefs.phone:
            raise DispatchError("PHONE_REQUIRED", "SMS channel selected but no phone on file", 422)
        if (prefs.quiet_hours_start is None) != (prefs.quiet_hours_end is None):
            raise DispatchError("QUIET_HOURS_INCOMPLETE", "Both quiet hour bounds are required", 422)
        if not prefs.channels:
            raise DispatchError("CHANNEL_REQUIRED", "At least one channel is required", 422)
        self._prefs[prefs.customer_id] = prefs
        return prefs


class Dispatcher:
    def __init__(self, store: PreferenceStore, now_fn=lambda: datetime.now(timezone.utc)) -> None:
        self._store = store
        self._now = now_fn
        self.outbox: list[Delivery] = []
        self._dedupe: dict[str, datetime] = {}

    def send(self, request: SendRequest) -> Delivery:
        if not templates.exists(request.template):
            raise DispatchError("TEMPLATE_UNKNOWN", f"Unknown template {request.template}", 422)
        prefs = self._store.get(request.customer_id)
        channel = self._choose_channel(prefs, request)
        now = self._now()

        if templates.is_marketing(request.template) and not prefs.marketing_opt_in:
            return self._record(request, channel, DeliveryStatus.SUPPRESSED_OPT_OUT, None, "", now)

        if request.dedupe_key:
            last = self._dedupe.get(request.dedupe_key)
            if last and now - last < DEDUPE_WINDOW:
                return self._record(request, channel, DeliveryStatus.SUPPRESSED_DUPLICATE, None, "", now)
            self._dedupe[request.dedupe_key] = now

        try:
            subject, body = templates.render(request.template, channel, request.variables)
        except templates.TemplateError as exc:
            raise DispatchError("TEMPLATE_RENDER_FAILED", str(exc), 422) from exc

        if channel is Channel.EMAIL and not prefs.email:
            return self._record(request, channel, DeliveryStatus.FAILED_NO_ADDRESS, subject, body, now)
        if channel is Channel.SMS and not prefs.phone:
            return self._record(request, channel, DeliveryStatus.FAILED_NO_ADDRESS, subject, body, now)

        if request.priority is not Priority.CRITICAL and self._in_quiet_hours(prefs, now):
            return self._record(request, channel, DeliveryStatus.QUEUED_QUIET_HOURS, subject, body, now)

        return self._record(request, channel, DeliveryStatus.SENT, subject, body, now)

    @staticmethod
    def _choose_channel(prefs: Preferences, request: SendRequest) -> Channel:
        if request.channel_override:
            if request.channel_override not in prefs.channels and request.priority is not Priority.CRITICAL:
                raise DispatchError("CHANNEL_NOT_ENABLED", "Customer has not enabled this channel", 422)
            return request.channel_override
        if request.priority in {Priority.HIGH, Priority.CRITICAL} and Channel.SMS in prefs.channels:
            return Channel.SMS
        if Channel.PUSH in prefs.channels and request.priority is Priority.LOW:
            return Channel.PUSH
        return prefs.channels[0]

    @staticmethod
    def _in_quiet_hours(prefs: Preferences, now_utc: datetime) -> bool:
        if prefs.quiet_hours_start is None or prefs.quiet_hours_end is None:
            return False
        local_hour = (now_utc.hour + prefs.timezone_offset_hours) % 24
        start, end = prefs.quiet_hours_start, prefs.quiet_hours_end
        if start == end:
            return False
        if start < end:
            return start <= local_hour < end
        return local_hour >= start or local_hour < end

    def _record(
        self,
        request: SendRequest,
        channel: Channel,
        status: DeliveryStatus,
        subject: str | None,
        body: str,
        now: datetime,
    ) -> Delivery:
        delivery = Delivery(
            notification_id=str(uuid.uuid4()),
            customer_id=request.customer_id,
            channel=channel,
            status=status,
            subject=subject,
            body=body,
            created_at=now,
        )
        self.outbox.append(delivery)
        return delivery

    def history(self, customer_id: str, limit: int = 50) -> list[Delivery]:
        return [d for d in self.outbox if d.customer_id == customer_id][-limit:]

    def release_quiet_hours_queue(self) -> int:
        released = 0
        now = self._now()
        for index, delivery in enumerate(self.outbox):
            if delivery.status is DeliveryStatus.QUEUED_QUIET_HOURS:
                prefs = self._store.get(delivery.customer_id)
                if not self._in_quiet_hours(prefs, now):
                    self.outbox[index] = delivery.model_copy(update={"status": DeliveryStatus.SENT})
                    released += 1
        return released


store = PreferenceStore()
dispatcher = Dispatcher(store)
