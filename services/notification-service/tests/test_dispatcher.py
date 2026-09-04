from datetime import datetime, timezone

import pytest

from app.models.notification import Channel, DeliveryStatus, Preferences, Priority, SendRequest
from app.services.dispatcher import DispatchError

VARS = {"first_name": "Jane", "amount": "$1,250.00", "last4": "4421"}


def test_normal_priority_uses_first_preferred_channel(dispatcher):
    delivery = dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables=VARS))
    assert delivery.status is DeliveryStatus.SENT
    assert delivery.channel is Channel.EMAIL
    assert delivery.subject == "Large transaction alert"
    assert "4421" in delivery.body


def test_high_priority_prefers_sms(dispatcher):
    delivery = dispatcher.send(
        SendRequest(customer_id="CUST-1", template="fraud_lock", variables=VARS, priority=Priority.HIGH)
    )
    assert delivery.channel is Channel.SMS
    assert delivery.subject is None


def test_low_priority_prefers_push(dispatcher):
    delivery = dispatcher.send(
        SendRequest(
            customer_id="CUST-1",
            template="statement_ready",
            variables={**VARS, "period": "February"},
            priority=Priority.LOW,
        )
    )
    assert delivery.channel is Channel.PUSH


def test_quiet_hours_queue_non_critical(dispatcher, clock):
    clock.at = datetime(2026, 3, 2, 4, 30, tzinfo=timezone.utc)  # 23:30 Eastern
    delivery = dispatcher.send(SendRequest(customer_id="CUST-1", template="low_balance", variables={**VARS, "threshold": "$100"}))
    assert delivery.status is DeliveryStatus.QUEUED_QUIET_HOURS


def test_critical_bypasses_quiet_hours(dispatcher, clock):
    clock.at = datetime(2026, 3, 2, 4, 30, tzinfo=timezone.utc)
    delivery = dispatcher.send(
        SendRequest(customer_id="CUST-1", template="fraud_lock", variables=VARS, priority=Priority.CRITICAL)
    )
    assert delivery.status is DeliveryStatus.SENT


def test_dedupe_key_suppresses_repeat(dispatcher):
    first = dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables=VARS, dedupe_key="k1"))
    second = dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables=VARS, dedupe_key="k1"))
    assert first.status is DeliveryStatus.SENT
    assert second.status is DeliveryStatus.SUPPRESSED_DUPLICATE


def test_marketing_requires_opt_in(dispatcher):
    delivery = dispatcher.send(
        SendRequest(customer_id="CUST-1", template="preferred_rewards_offer", variables={**VARS, "tier": "Gold"})
    )
    assert delivery.status is DeliveryStatus.SUPPRESSED_OPT_OUT


def test_channel_override_must_be_enabled(dispatcher):
    with pytest.raises(DispatchError) as exc:
        dispatcher.send(
            SendRequest(
                customer_id="CUST-EMAIL-ONLY", template="large_transaction", variables=VARS, channel_override=Channel.SMS
            )
        )
    assert exc.value.code == "CHANNEL_NOT_ENABLED"


def test_unknown_template_rejected(dispatcher):
    with pytest.raises(DispatchError) as exc:
        dispatcher.send(SendRequest(customer_id="CUST-1", template="nope"))
    assert exc.value.code == "TEMPLATE_UNKNOWN"


def test_missing_variables_rejected(dispatcher):
    with pytest.raises(DispatchError) as exc:
        dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables={"first_name": "J"}))
    assert exc.value.code == "TEMPLATE_RENDER_FAILED"
    assert "amount" in exc.value.message


def test_unknown_customer(dispatcher):
    with pytest.raises(DispatchError) as exc:
        dispatcher.send(SendRequest(customer_id="ghost", template="large_transaction", variables=VARS))
    assert exc.value.status == 404


def test_history_filters_by_customer(dispatcher):
    dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables=VARS))
    dispatcher.send(SendRequest(customer_id="CUST-EMAIL-ONLY", template="large_transaction", variables=VARS))
    assert len(dispatcher.history("CUST-1")) == 1


def test_release_queue_after_quiet_hours(dispatcher, clock):
    clock.at = datetime(2026, 3, 2, 4, 30, tzinfo=timezone.utc)
    dispatcher.send(SendRequest(customer_id="CUST-1", template="large_transaction", variables=VARS))
    clock.at = datetime(2026, 3, 2, 14, 0, tzinfo=timezone.utc)
    assert dispatcher.release_quiet_hours_queue() == 1
    assert dispatcher.history("CUST-1")[0].status is DeliveryStatus.SENT


def test_preferences_validation(store):
    with pytest.raises(DispatchError) as exc:
        store.upsert(Preferences(customer_id="X", channels=[Channel.SMS]))
    assert exc.value.code == "PHONE_REQUIRED"
    with pytest.raises(DispatchError) as exc:
        store.upsert(Preferences(customer_id="X", email="x@example.com", quiet_hours_start=22))
    assert exc.value.code == "QUIET_HOURS_INCOMPLETE"
