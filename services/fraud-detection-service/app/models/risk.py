from datetime import datetime
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field


class Channel(str, Enum):
    ONLINE = "ONLINE"
    MOBILE = "MOBILE"
    BRANCH = "BRANCH"
    ATM = "ATM"
    WIRE = "WIRE"
    CARD_PRESENT = "CARD_PRESENT"
    CARD_NOT_PRESENT = "CARD_NOT_PRESENT"


class Decision(str, Enum):
    APPROVE = "APPROVE"
    REVIEW = "REVIEW"
    DECLINE = "DECLINE"


class ScoreRequest(BaseModel):
    transaction_id: str = Field(..., min_length=1)
    account_id: str = Field(..., min_length=1)
    amount: Decimal = Field(..., gt=0, max_digits=15, decimal_places=2)
    currency: str = Field(default="USD", min_length=3, max_length=3)
    channel: Channel
    merchant_category: str | None = None
    counterparty_country: str = Field(default="US", min_length=2, max_length=2)
    device_id: str | None = None
    ip_country: str | None = None
    occurred_at: datetime | None = None


class RiskSignal(BaseModel):
    code: str
    weight: int
    detail: str


class ScoreResponse(BaseModel):
    transaction_id: str
    score: int = Field(..., ge=0, le=100)
    decision: Decision
    signals: list[RiskSignal]
    model_version: str


class CaseStatus(str, Enum):
    OPEN = "OPEN"
    INVESTIGATING = "INVESTIGATING"
    CONFIRMED_FRAUD = "CONFIRMED_FRAUD"
    FALSE_POSITIVE = "FALSE_POSITIVE"


class FraudCase(BaseModel):
    case_id: str
    transaction_id: str
    account_id: str
    score: int
    status: CaseStatus
    assigned_to: str | None = None
    opened_at: datetime
    sar_filed: bool = False
    notes: list[str] = Field(default_factory=list)
