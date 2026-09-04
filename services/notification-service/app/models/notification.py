from datetime import datetime
from enum import Enum

from pydantic import BaseModel, EmailStr, Field


class Channel(str, Enum):
    EMAIL = "EMAIL"
    SMS = "SMS"
    PUSH = "PUSH"


class Priority(str, Enum):
    LOW = "LOW"
    NORMAL = "NORMAL"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"  # fraud / security; bypasses quiet hours


class Preferences(BaseModel):
    customer_id: str
    email: EmailStr | None = None
    phone: str | None = Field(default=None, pattern=r"^\+1\d{10}$")
    channels: list[Channel] = Field(default_factory=lambda: [Channel.EMAIL])
    quiet_hours_start: int | None = Field(default=None, ge=0, le=23)
    quiet_hours_end: int | None = Field(default=None, ge=0, le=23)
    timezone_offset_hours: int = Field(default=-5, ge=-12, le=14)
    marketing_opt_in: bool = False


class SendRequest(BaseModel):
    customer_id: str = Field(..., min_length=1)
    template: str = Field(..., min_length=1)
    variables: dict[str, str] = Field(default_factory=dict)
    priority: Priority = Priority.NORMAL
    channel_override: Channel | None = None
    dedupe_key: str | None = None


class DeliveryStatus(str, Enum):
    SENT = "SENT"
    QUEUED_QUIET_HOURS = "QUEUED_QUIET_HOURS"
    SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"
    SUPPRESSED_OPT_OUT = "SUPPRESSED_OPT_OUT"
    FAILED_NO_ADDRESS = "FAILED_NO_ADDRESS"


class Delivery(BaseModel):
    notification_id: str
    customer_id: str
    channel: Channel
    status: DeliveryStatus
    subject: str | None
    body: str
    created_at: datetime
