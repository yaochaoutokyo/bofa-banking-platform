import pytest

from app.models.notification import Channel
from app.services import templates


def test_render_email_includes_subject():
    subject, body = templates.render(
        "large_transaction", Channel.EMAIL, {"first_name": "Jane", "amount": "$5.00", "last4": "0001"}
    )
    assert subject == "Large transaction alert"
    assert body.startswith("Hi Jane")


def test_sms_truncated_to_160():
    subject, body = templates.render(
        "large_transaction", Channel.SMS, {"first_name": "J", "amount": "x" * 150, "last4": "0001"}
    )
    assert subject is None
    assert len(body) == 160
    assert body.endswith("...")


def test_oversized_variable_rejected():
    with pytest.raises(templates.TemplateError):
        templates.render("large_transaction", Channel.PUSH, {"amount": "x" * 201, "last4": "0001"})


def test_required_variables():
    assert templates.required_variables("low_balance", Channel.SMS) == {"last4", "threshold"}


def test_unknown_template():
    with pytest.raises(templates.TemplateError):
        templates.render("missing", Channel.EMAIL, {})
