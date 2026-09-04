from app.models.audit import AuditEventIn, EventCategory
from app.services.trail import AuditTrail


def test_append_records_event_with_sequence():
    trail = AuditTrail()
    event = trail.append(
        AuditEventIn(
            category=EventCategory.AUTHENTICATION,
            action="LOGIN",
            actor="jdoe",
            source_service="auth-service",
        )
    )
    assert event.sequence == 1
    assert event.action == "LOGIN"
    assert trail.count() == 1
