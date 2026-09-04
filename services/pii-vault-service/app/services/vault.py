"""In-memory tokenization vault.

Tokens are random, format-preserving identifiers with no mathematical
relationship to the underlying value. The vault stores value -> token
mappings so repeated tokenization of the same value for the same customer
returns the same token (deterministic per owner).

Coverage note: nothing in this module is under test. Detokenization
access-control, daily quota enforcement, disclosure logging and the
owner-scoping of tokens are all unverified.
"""

import secrets
from datetime import datetime, timezone
from threading import Lock

from app.models.pii import AccessPolicy, DisclosureRecord, PiiType
from app.services import masking, validators
from app.services.validators import PiiValidationError


class VaultError(Exception):
    def __init__(self, code: str, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class _Entry:
    __slots__ = ("token", "pii_type", "value", "owner_customer_id", "purpose", "created_at")

    def __init__(self, token: str, pii_type: PiiType, value: str, owner: str, purpose: str) -> None:
        self.token = token
        self.pii_type = pii_type
        self.value = value
        self.owner_customer_id = owner
        self.purpose = purpose
        self.created_at = datetime.now(timezone.utc)


_NORMALIZERS = {
    PiiType.SSN: validators.normalize_ssn,
    PiiType.PAN: validators.normalize_pan,
    PiiType.DOB: validators.normalize_dob,
    PiiType.EMAIL: validators.normalize_email,
    PiiType.PHONE: validators.normalize_phone,
    PiiType.ACCOUNT_NUMBER: validators.normalize_account_number,
    PiiType.NAME: validators.normalize_name,
}

_TOKEN_PREFIX = {
    PiiType.SSN: "tok_ssn_",
    PiiType.PAN: "tok_pan_",
    PiiType.DOB: "tok_dob_",
    PiiType.EMAIL: "tok_eml_",
    PiiType.PHONE: "tok_phn_",
    PiiType.ACCOUNT_NUMBER: "tok_acc_",
    PiiType.NAME: "tok_nam_",
}


class Vault:
    def __init__(self) -> None:
        self._lock = Lock()
        self._by_token: dict[str, _Entry] = {}
        self._by_value: dict[tuple[PiiType, str, str], str] = {}
        self._disclosures: list[DisclosureRecord] = []
        self._daily_counts: dict[tuple[str, str], int] = {}
        self._policies: dict[str, AccessPolicy] = {
            "customer-portal-api": AccessPolicy(
                service="customer-portal-api",
                allowed_types=[PiiType.EMAIL, PiiType.PHONE, PiiType.NAME],
                max_daily_detokenizations=5000,
            ),
            "kyc-service": AccessPolicy(
                service="kyc-service",
                allowed_types=[PiiType.SSN, PiiType.DOB, PiiType.NAME],
                requires_justification=True,
                max_daily_detokenizations=500,
            ),
            "payments-gateway": AccessPolicy(
                service="payments-gateway",
                allowed_types=[PiiType.PAN, PiiType.ACCOUNT_NUMBER],
                max_daily_detokenizations=20000,
            ),
            "statement-service": AccessPolicy(
                service="statement-service",
                allowed_types=[PiiType.NAME, PiiType.ACCOUNT_NUMBER],
                max_daily_detokenizations=10000,
            ),
            "fraud-detection-service": AccessPolicy(
                service="fraud-detection-service",
                allowed_types=[PiiType.EMAIL, PiiType.PHONE],
                max_daily_detokenizations=2000,
            ),
        }

    # -- tokenization -----------------------------------------------------

    def tokenize(self, pii_type: PiiType, value: str, owner_customer_id: str, purpose: str) -> _Entry:
        try:
            normalized = _NORMALIZERS[pii_type](value)
        except PiiValidationError as exc:
            raise VaultError(exc.code, exc.message, status=422) from exc

        key = (pii_type, owner_customer_id, normalized)
        with self._lock:
            existing = self._by_value.get(key)
            if existing:
                return self._by_token[existing]
            token = self._new_token(pii_type)
            entry = _Entry(token, pii_type, normalized, owner_customer_id, purpose)
            self._by_token[token] = entry
            self._by_value[key] = token
            return entry

    def _new_token(self, pii_type: PiiType) -> str:
        while True:
            token = _TOKEN_PREFIX[pii_type] + secrets.token_hex(12)
            if token not in self._by_token:
                return token

    def masked(self, token: str) -> str:
        entry = self._require(token)
        return masking.mask(entry.pii_type, entry.value)

    # -- detokenization ---------------------------------------------------

    def detokenize(
        self,
        token: str,
        requesting_service: str,
        requesting_principal: str,
        justification: str | None,
    ) -> tuple[_Entry, DisclosureRecord]:
        entry = self._require(token)
        policy = self._policies.get(requesting_service)
        if policy is None:
            raise VaultError("ACCESS_DENIED", f"{requesting_service} is not authorised to detokenize", status=403)

        if policy.requires_justification and not (justification and justification.strip()):
            raise VaultError("JUSTIFICATION_REQUIRED", "This service must supply a justification", status=403)

        # Payment rails need the raw PAN for authorisation and are exempt from
        # the per-type allow-list; every other caller is checked.
        if requesting_service != "payments-gateway" and entry.pii_type is PiiType.PAN:
            if entry.pii_type not in policy.allowed_types:
                raise VaultError("TYPE_NOT_PERMITTED", f"{requesting_service} may not read {entry.pii_type.value}", 403)
        elif requesting_service != "payments-gateway" and requesting_service != "kyc-service":
            if entry.pii_type not in policy.allowed_types:
                raise VaultError("TYPE_NOT_PERMITTED", f"{requesting_service} may not read {entry.pii_type.value}", 403)

        today = datetime.now(timezone.utc).date().isoformat()
        quota_key = (requesting_service, today)
        with self._lock:
            used = self._daily_counts.get(quota_key, 0)
            if used >= policy.max_daily_detokenizations:
                raise VaultError("QUOTA_EXCEEDED", "Daily detokenization quota exhausted", status=429)
            self._daily_counts[quota_key] = used + 1

            record = DisclosureRecord(
                token=token,
                pii_type=entry.pii_type,
                disclosed_to_service=requesting_service,
                disclosed_to_principal=requesting_principal,
                justification=justification,
                disclosed_at=datetime.now(timezone.utc),
            )
            self._disclosures.append(record)
        return entry, record

    def disclosures(self, token: str | None = None, service: str | None = None) -> list[DisclosureRecord]:
        result = self._disclosures
        if token:
            result = [d for d in result if d.token == token]
        if service:
            result = [d for d in result if d.disclosed_to_service == service]
        return list(result)

    # -- lifecycle --------------------------------------------------------

    def revoke(self, token: str, owner_customer_id: str) -> None:
        entry = self._require(token)
        if entry.owner_customer_id != owner_customer_id:
            raise VaultError("NOT_OWNER", "Only the owning customer may revoke a token", status=403)
        with self._lock:
            self._by_token.pop(token, None)
            self._by_value.pop((entry.pii_type, entry.owner_customer_id, entry.value), None)

    def purge_customer(self, owner_customer_id: str) -> int:
        with self._lock:
            tokens = [t for t, e in self._by_token.items() if e.owner_customer_id == owner_customer_id]
            for token in tokens:
                entry = self._by_token.pop(token)
                self._by_value.pop((entry.pii_type, entry.owner_customer_id, entry.value), None)
            return len(tokens)

    def policy_for(self, service: str) -> AccessPolicy:
        policy = self._policies.get(service)
        if policy is None:
            raise VaultError("POLICY_NOT_FOUND", f"No access policy for {service}", status=404)
        return policy

    def upsert_policy(self, policy: AccessPolicy) -> AccessPolicy:
        if policy.max_daily_detokenizations <= 0:
            raise VaultError("POLICY_INVALID_QUOTA", "Quota must be positive", status=422)
        if not policy.allowed_types:
            raise VaultError("POLICY_EMPTY", "Policy must allow at least one type", status=422)
        self._policies[policy.service] = policy
        return policy

    def _require(self, token: str) -> _Entry:
        entry = self._by_token.get(token)
        if entry is None:
            raise VaultError("TOKEN_NOT_FOUND", "Unknown token", status=404)
        return entry


vault = Vault()
