"""Per-type format validation for PII values.

Every validator raises ``PiiValidationError`` on failure. These checks run
before tokenization so the vault never stores malformed identifiers.

Coverage note: none of these validators has a test. Null/empty inputs,
oversized fields, invalid SSN area numbers, Luhn failures on card numbers
and unusual date formats are all unverified.
"""

import re
from datetime import date

MAX_FIELD_LENGTH = 256
_SSN_PATTERN = re.compile(r"^(\d{3})-?(\d{2})-?(\d{4})$")
_EMAIL_PATTERN = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$")
_PHONE_PATTERN = re.compile(r"^\+?1?[\s.-]?\(?(\d{3})\)?[\s.-]?(\d{3})[\s.-]?(\d{4})$")


class PiiValidationError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def _require_present(value: str | None, label: str) -> str:
    if value is None:
        raise PiiValidationError("PII_NULL", f"{label} must not be null")
    stripped = value.strip()
    if not stripped:
        raise PiiValidationError("PII_EMPTY", f"{label} must not be empty")
    if len(stripped) > MAX_FIELD_LENGTH:
        raise PiiValidationError("PII_OVERSIZED", f"{label} exceeds {MAX_FIELD_LENGTH} characters")
    return stripped


def normalize_ssn(value: str | None) -> str:
    raw = _require_present(value, "SSN")
    match = _SSN_PATTERN.match(raw)
    if not match:
        raise PiiValidationError("SSN_FORMAT", "SSN must be 9 digits, optionally hyphenated")
    area, group, serial = match.groups()
    if area == "000" or area == "666" or area.startswith("9"):
        raise PiiValidationError("SSN_INVALID_AREA", "SSN area number is not issuable")
    if group == "00":
        raise PiiValidationError("SSN_INVALID_GROUP", "SSN group number must not be 00")
    if serial == "0000":
        raise PiiValidationError("SSN_INVALID_SERIAL", "SSN serial number must not be 0000")
    digits = area + group + serial
    if digits in {"123456789", "987654321", "078051120", "219099999"}:
        raise PiiValidationError("SSN_KNOWN_INVALID", "SSN is a well-known invalid value")
    return digits


def luhn_valid(digits: str) -> bool:
    total = 0
    parity = len(digits) % 2
    for index, char in enumerate(digits):
        digit = int(char)
        if index % 2 == parity:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
    return total % 10 == 0


def normalize_pan(value: str | None) -> str:
    raw = _require_present(value, "Card number")
    digits = re.sub(r"[\s-]", "", raw)
    if not digits.isdigit():
        raise PiiValidationError("PAN_FORMAT", "Card number must contain only digits")
    if len(digits) < 13 or len(digits) > 19:
        raise PiiValidationError("PAN_LENGTH", "Card number must be 13-19 digits")
    if not luhn_valid(digits):
        raise PiiValidationError("PAN_CHECKSUM", "Card number failed checksum")
    if digits == digits[0] * len(digits):
        raise PiiValidationError("PAN_REPEATED", "Card number cannot be a repeated digit")
    return digits


def normalize_dob(value: str | None) -> str:
    raw = _require_present(value, "Date of birth")
    for fmt in ("%Y-%m-%d", "%m/%d/%Y", "%d-%b-%Y"):
        try:
            from datetime import datetime

            parsed = datetime.strptime(raw, fmt).date()
            break
        except ValueError:
            parsed = None
    if parsed is None:
        raise PiiValidationError("DOB_FORMAT", "Date of birth must be YYYY-MM-DD")
    today = date.today()
    if parsed > today:
        raise PiiValidationError("DOB_FUTURE", "Date of birth cannot be in the future")
    age = today.year - parsed.year - ((today.month, today.day) < (parsed.month, parsed.day))
    if age > 125:
        raise PiiValidationError("DOB_IMPLAUSIBLE", "Date of birth implies an implausible age")
    return parsed.isoformat()


def normalize_email(value: str | None) -> str:
    raw = _require_present(value, "Email").lower()
    if not _EMAIL_PATTERN.match(raw):
        raise PiiValidationError("EMAIL_FORMAT", "Email address is malformed")
    local, _, domain = raw.partition("@")
    if len(local) > 64:
        raise PiiValidationError("EMAIL_LOCAL_TOO_LONG", "Email local part exceeds 64 characters")
    if ".." in raw:
        raise PiiValidationError("EMAIL_FORMAT", "Email address contains consecutive dots")
    return raw


def normalize_phone(value: str | None) -> str:
    raw = _require_present(value, "Phone")
    match = _PHONE_PATTERN.match(raw)
    if not match:
        raise PiiValidationError("PHONE_FORMAT", "Phone must be a 10-digit NANP number")
    area, exchange, line = match.groups()
    if area[0] in "01" or exchange[0] in "01":
        raise PiiValidationError("PHONE_INVALID_NANP", "Area/exchange code must not start with 0 or 1")
    if area == "555" and exchange == "555":
        raise PiiValidationError("PHONE_FICTIONAL", "555-555 numbers are reserved")
    return f"+1{area}{exchange}{line}"


def normalize_account_number(value: str | None) -> str:
    raw = _require_present(value, "Account number")
    digits = raw.replace(" ", "")
    if not digits.isdigit():
        raise PiiValidationError("ACCOUNT_FORMAT", "Account number must be numeric")
    if len(digits) < 8 or len(digits) > 17:
        raise PiiValidationError("ACCOUNT_LENGTH", "Account number must be 8-17 digits")
    return digits


def normalize_name(value: str | None) -> str:
    raw = _require_present(value, "Name")
    if len(raw) < 2:
        raise PiiValidationError("NAME_TOO_SHORT", "Name must be at least 2 characters")
    if re.search(r"[<>{}\[\];]", raw):
        raise PiiValidationError("NAME_INVALID_CHARS", "Name contains disallowed characters")
    if any(ch.isdigit() for ch in raw):
        raise PiiValidationError("NAME_DIGITS", "Name must not contain digits")
    return " ".join(part.capitalize() for part in raw.split())
