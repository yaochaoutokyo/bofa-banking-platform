"""Message templates with strict variable substitution."""

import re

from app.models.notification import Channel

_VAR = re.compile(r"\{\{\s*([a-z_][a-z0-9_]*)\s*\}\}")


class TemplateError(ValueError):
    pass


_TEMPLATES: dict[str, dict[str, str]] = {
    "large_transaction": {
        "subject": "Large transaction alert",
        "email": "Hi {{ first_name }}, a transaction of {{ amount }} posted to account ending {{ last4 }}.",
        "sms": "BofA: {{ amount }} posted to acct ...{{ last4 }}. Not you? Call 800-432-1000.",
        "push": "{{ amount }} posted to ...{{ last4 }}",
        "marketing": "false",
    },
    "low_balance": {
        "subject": "Low balance warning",
        "email": "Hi {{ first_name }}, your balance for account ending {{ last4 }} is below {{ threshold }}.",
        "sms": "BofA: balance on ...{{ last4 }} is below {{ threshold }}.",
        "push": "Balance below {{ threshold }} on ...{{ last4 }}",
        "marketing": "false",
    },
    "fraud_lock": {
        "subject": "Security alert: card locked",
        "email": "Hi {{ first_name }}, we locked card ending {{ last4 }} after suspicious activity. Reply to confirm.",
        "sms": "BofA SECURITY: card ...{{ last4 }} locked. Reply YES if this was you.",
        "push": "Card ...{{ last4 }} locked for your protection",
        "marketing": "false",
    },
    "statement_ready": {
        "subject": "Your statement is ready",
        "email": "Hi {{ first_name }}, your {{ period }} statement for account ending {{ last4 }} is ready.",
        "sms": "BofA: your {{ period }} statement is ready.",
        "push": "{{ period }} statement ready",
        "marketing": "false",
    },
    "preferred_rewards_offer": {
        "subject": "You qualify for Preferred Rewards",
        "email": "Hi {{ first_name }}, you're eligible for Preferred Rewards {{ tier }}.",
        "sms": "BofA: you're eligible for Preferred Rewards {{ tier }}. Reply STOP to opt out.",
        "push": "Eligible for Preferred Rewards {{ tier }}",
        "marketing": "true",
    },
}


def exists(name: str) -> bool:
    return name in _TEMPLATES


def is_marketing(name: str) -> bool:
    return _TEMPLATES[name]["marketing"] == "true"


def required_variables(name: str, channel: Channel) -> set[str]:
    return set(_VAR.findall(_TEMPLATES[name][channel.value.lower()]))


def render(name: str, channel: Channel, variables: dict[str, str]) -> tuple[str | None, str]:
    if name not in _TEMPLATES:
        raise TemplateError(f"Unknown template '{name}'")
    template = _TEMPLATES[name]
    body_template = template[channel.value.lower()]
    missing = set(_VAR.findall(body_template)) - set(variables)
    if missing:
        raise TemplateError(f"Missing variables: {', '.join(sorted(missing))}")
    for key, value in variables.items():
        if len(value) > 200:
            raise TemplateError(f"Variable '{key}' exceeds 200 characters")

    def _sub(match: re.Match[str]) -> str:
        return variables[match.group(1)]

    body = _VAR.sub(_sub, body_template)
    subject = template["subject"] if channel is Channel.EMAIL else None
    if channel is Channel.SMS and len(body) > 160:
        body = body[:157] + "..."
    return subject, body
