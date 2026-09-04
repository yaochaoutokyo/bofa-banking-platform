from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field


class PiiType(str, Enum):
    SSN = "SSN"
    PAN = "PAN"  # primary account number (card)
    DOB = "DOB"
    EMAIL = "EMAIL"
    PHONE = "PHONE"
    ACCOUNT_NUMBER = "ACCOUNT_NUMBER"
    NAME = "NAME"


class TokenizeRequest(BaseModel):
    pii_type: PiiType
    value: str = Field(..., description="Raw PII value. Validated per type before tokenization.")
    owner_customer_id: str = Field(..., min_length=1)
    purpose: str = Field(default="storage", description="Business purpose recorded for GLBA disclosure logs.")


class TokenizeResponse(BaseModel):
    token: str
    pii_type: PiiType
    masked: str
    created_at: datetime


class DetokenizeRequest(BaseModel):
    token: str
    requesting_service: str
    requesting_principal: str
    justification: str | None = None


class DetokenizeResponse(BaseModel):
    token: str
    pii_type: PiiType
    value: str
    disclosed_to: str
    disclosed_at: datetime


class MaskRequest(BaseModel):
    pii_type: PiiType
    value: str


class MaskResponse(BaseModel):
    masked: str


class BulkMaskRequest(BaseModel):
    record: dict[str, str]
    field_types: dict[str, PiiType]


class AccessPolicy(BaseModel):
    service: str
    allowed_types: list[PiiType]
    requires_justification: bool = False
    max_daily_detokenizations: int = 1000


class DisclosureRecord(BaseModel):
    token: str
    pii_type: PiiType
    disclosed_to_service: str
    disclosed_to_principal: str
    justification: str | None
    disclosed_at: datetime
