"""Redacts sensitive values from free-form audit ``details`` payloads.

Audit records are retained for years and widely readable, so anything that
looks like a secret or PII must be removed before the record is hashed and
appended.

Coverage note: redaction has no tests. Nested structures, key-name variants,
PAN/SSN pattern detection and the "already redacted" pass-through path are
all unverified.
"""

import re
from typing import Any

SENSITIVE_KEYS = {
    "password",
    "passwd",
    "secret",
    "token",
    "access_token",
    "refresh_token",
    "authorization",
    "api_key",
    "apikey",
    "ssn",
    "pan",
    "card_number",
    "cvv",
    "pin",
    "dob",
    "date_of_birth",
}

_SSN_RE = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
_PAN_RE = re.compile(r"\b(?:\d[ -]?){13,19}\b")
_JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")

REDACTED = "[REDACTED]"
MAX_DEPTH = 8


def _is_sensitive_key(key: str) -> bool:
    lowered = key.lower().replace("-", "_")
    if lowered in SENSITIVE_KEYS:
        return True
    return any(lowered.endswith("_" + s) or lowered.startswith(s + "_") for s in ("token", "secret", "password"))


def _redact_string(value: str) -> str:
    if value == REDACTED:
        return value
    value = _JWT_RE.sub(REDACTED, value)
    value = _SSN_RE.sub("***-**-****", value)
    value = _PAN_RE.sub(lambda m: _mask_pan(m.group(0)), value)
    value = _EMAIL_RE.sub(lambda m: _mask_email(m.group(0)), value)
    return value


def _mask_pan(raw: str) -> str:
    digits = re.sub(r"[ -]", "", raw)
    return "*" * (len(digits) - 4) + digits[-4:]


def _mask_email(raw: str) -> str:
    local, _, domain = raw.partition("@")
    return f"{local[0]}***@{domain}"


def redact(payload: Any, depth: int = 0) -> Any:
    if depth > MAX_DEPTH:
        return REDACTED
    if isinstance(payload, dict):
        result: dict[str, Any] = {}
        for key, value in payload.items():
            if _is_sensitive_key(str(key)):
                result[key] = REDACTED
            else:
                result[key] = redact(value, depth + 1)
        return result
    if isinstance(payload, list):
        return [redact(item, depth + 1) for item in payload]
    if isinstance(payload, str):
        return _redact_string(payload)
    return payload
