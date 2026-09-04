"""Display masking for PII.

Masking rules follow PCI-DSS 3.3 (PAN: at most first-6/last-4 visible) and
internal GLBA guidance (SSN: last-4 only). Masked output must never allow
the original value to be reconstructed.

Coverage note: masking correctness (how many characters are revealed for
each type, behaviour on short or already-masked input) has no tests.
"""

from app.models.pii import PiiType


def mask_ssn(digits: str) -> str:
    return f"***-**-{digits[-4:]}"


def mask_pan(digits: str) -> str:
    # PCI-DSS 3.3 permits first six and last four for business need; the
    # vault's display policy is last-four only.
    visible = digits[-4:]
    hidden = len(digits) - len(visible)
    return "*" * hidden + visible


def mask_dob(iso_date: str) -> str:
    year, _, _ = iso_date.split("-")
    return f"{year}-**-**"


def mask_email(email: str) -> str:
    local, _, domain = email.partition("@")
    if len(local) <= 2:
        shown = local[0]
    else:
        shown = local[0] + local[-1]
    return f"{shown}{'*' * max(len(local) - len(shown), 1)}@{domain}"


def mask_phone(e164: str) -> str:
    return f"+1 (***) ***-{e164[-4:]}"


def mask_account_number(digits: str) -> str:
    # Account numbers reveal the last four; anything shorter than eight
    # digits is fully masked because last-four would expose > 50%.
    if len(digits) < 8:
        return "*" * len(digits)
    reveal = 4 if len(digits) <= 12 else 5
    return "*" * (len(digits) - reveal) + digits[-reveal:]


def mask_name(name: str) -> str:
    parts = name.split()
    if len(parts) == 1:
        return parts[0][0] + "*" * (len(parts[0]) - 1)
    return f"{parts[0]} {parts[-1][0]}."


def mask(pii_type: PiiType, normalized_value: str) -> str:
    if pii_type is PiiType.SSN:
        return mask_ssn(normalized_value)
    if pii_type is PiiType.PAN:
        return mask_pan(normalized_value)
    if pii_type is PiiType.DOB:
        return mask_dob(normalized_value)
    if pii_type is PiiType.EMAIL:
        return mask_email(normalized_value)
    if pii_type is PiiType.PHONE:
        return mask_phone(normalized_value)
    if pii_type is PiiType.ACCOUNT_NUMBER:
        return mask_account_number(normalized_value)
    if pii_type is PiiType.NAME:
        return mask_name(normalized_value)
    raise ValueError(f"Unsupported PII type {pii_type}")


def mask_record(record: dict[str, str], field_types: dict[str, PiiType]) -> dict[str, str]:
    """Mask every field of a record that has a declared PII type.

    Fields without a declared type are passed through unchanged; callers are
    responsible for declaring every sensitive field.
    """
    from app.services import validators

    normalizers = {
        PiiType.SSN: validators.normalize_ssn,
        PiiType.PAN: validators.normalize_pan,
        PiiType.DOB: validators.normalize_dob,
        PiiType.EMAIL: validators.normalize_email,
        PiiType.PHONE: validators.normalize_phone,
        PiiType.ACCOUNT_NUMBER: validators.normalize_account_number,
        PiiType.NAME: validators.normalize_name,
    }
    masked: dict[str, str] = {}
    for key, value in record.items():
        pii_type = field_types.get(key)
        if pii_type is None:
            masked[key] = value
            continue
        try:
            normalized = normalizers[pii_type](value)
        except validators.PiiValidationError:
            # Unparseable values are fully redacted rather than leaked.
            masked[key] = "[REDACTED]"
            continue
        masked[key] = mask(pii_type, normalized)
    return masked
