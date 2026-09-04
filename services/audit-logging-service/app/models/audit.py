from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class Severity(str, Enum):
    INFO = "INFO"
    NOTICE = "NOTICE"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class EventCategory(str, Enum):
    AUTHENTICATION = "AUTHENTICATION"
    AUTHORIZATION = "AUTHORIZATION"
    TRANSACTION = "TRANSACTION"
    PII_ACCESS = "PII_ACCESS"
    CONFIGURATION = "CONFIGURATION"
    ADMIN = "ADMIN"


class AuditEventIn(BaseModel):
    category: EventCategory
    action: str = Field(..., min_length=1, max_length=120)
    actor: str | None = Field(default=None, description="Principal performing the action. Required by policy.")
    actor_type: str = Field(default="user")
    source_service: str = Field(..., min_length=1)
    subject: str | None = Field(default=None, description="Resource acted upon, e.g. account:ACC-1001")
    outcome: str = Field(default="SUCCESS")
    severity: Severity = Severity.INFO
    occurred_at: datetime | None = Field(default=None, description="Producer timestamp; defaults to receipt time.")
    correlation_id: str | None = None
    details: dict[str, Any] = Field(default_factory=dict)


class AuditEvent(AuditEventIn):
    sequence: int
    event_id: str
    recorded_at: datetime
    previous_hash: str
    hash: str


class IntegrityReport(BaseModel):
    total_events: int
    verified: bool
    first_bad_sequence: int | None
    head_hash: str


class RetentionPolicy(BaseModel):
    category: EventCategory
    retain_years: int = Field(..., ge=1, le=25)
    legal_hold: bool = False


class QueryFilter(BaseModel):
    category: EventCategory | None = None
    actor: str | None = None
    subject: str | None = None
    since: datetime | None = None
    until: datetime | None = None
    limit: int = Field(default=100, ge=1, le=1000)
