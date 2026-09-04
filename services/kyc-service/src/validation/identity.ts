import { Applicant, KycError } from "../types";

/*
 * CIP data element validation (31 CFR 1020.220). Untested — every branch below
 * is a regulatory required data element: name, DOB, address, identification
 * number. Edge cases with no coverage: leap-day DOBs, applicants exactly 18
 * today, ITIN vs SSN prefixes, PO Box addresses, expired documents on the
 * boundary day, non-Latin names, and sanctioned issuing countries.
 */

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function validateName(value: string | undefined, field: string): string {
  if (!value || value.trim().length === 0) {
    throw new KycError("NAME_REQUIRED", `${field} is required`, 422);
  }
  const trimmed = value.trim();
  if (trimmed.length > 60) {
    throw new KycError("NAME_TOO_LONG", `${field} exceeds 60 characters`, 422);
  }
  if (!/^[A-Za-z\u00C0-\u024F' \-.]+$/.test(trimmed)) {
    throw new KycError("NAME_INVALID", `${field} contains unsupported characters`, 422);
  }
  return trimmed;
}

export function validateDateOfBirth(value: string | undefined, today: Date): string {
  if (!value || !ISO_DATE.test(value)) {
    throw new KycError("DOB_INVALID", "dateOfBirth must be YYYY-MM-DD", 422);
  }
  const dob = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(dob.getTime()) || dob.toISOString().slice(0, 10) !== value) {
    throw new KycError("DOB_INVALID", "dateOfBirth is not a real date", 422);
  }
  if (dob > today) {
    throw new KycError("DOB_FUTURE", "dateOfBirth cannot be in the future", 422);
  }
  const age = ageOn(dob, today);
  if (age < 18) {
    throw new KycError("APPLICANT_MINOR", "Applicant must be at least 18 (custodial accounts use a different flow)", 422);
  }
  if (age > 120) {
    throw new KycError("DOB_IMPLAUSIBLE", "dateOfBirth implies an age over 120", 422);
  }
  return value;
}

export function ageOn(dob: Date, today: Date): number {
  let age = today.getUTCFullYear() - dob.getUTCFullYear();
  const beforeBirthday =
    today.getUTCMonth() < dob.getUTCMonth() ||
    (today.getUTCMonth() === dob.getUTCMonth() && today.getUTCDate() < dob.getUTCDate());
  if (beforeBirthday) {
    age -= 1;
  }
  return age;
}

/** Accepts SSN or ITIN; returns digits only. */
export function validateTaxId(value: string | undefined, citizenship: string): string | undefined {
  if (!value) {
    if (citizenship === "US") {
      throw new KycError("TAX_ID_REQUIRED", "US persons must provide an SSN or ITIN", 422);
    }
    return undefined;
  }
  const digits = value.replace(/-/g, "");
  if (!/^\d{9}$/.test(digits)) {
    throw new KycError("TAX_ID_INVALID", "Tax identification number must be 9 digits", 422);
  }
  const area = digits.slice(0, 3);
  const group = digits.slice(3, 5);
  const serial = digits.slice(5);
  const isItin = area.startsWith("9") && /^(5\d|6[0-5]|7\d|8[0-8]|9[0-2]|9[4-9])$/.test(group);
  if (!isItin) {
    if (area === "000" || area === "666" || area.startsWith("9")) {
      throw new KycError("TAX_ID_INVALID", "SSN area number is not issuable", 422);
    }
    if (group === "00" || serial === "0000") {
      throw new KycError("TAX_ID_INVALID", "SSN group/serial cannot be zero", 422);
    }
  }
  return digits;
}

export function validateAddress(address: Applicant["address"] | undefined): Applicant["address"] {
  if (!address) {
    throw new KycError("ADDRESS_REQUIRED", "A residential address is required", 422);
  }
  const line1 = (address.line1 ?? "").trim();
  if (line1.length < 3) {
    throw new KycError("ADDRESS_INVALID", "Address line 1 is required", 422);
  }
  if (/^p\.?\s*o\.?\s*box/i.test(line1)) {
    throw new KycError("ADDRESS_PO_BOX", "CIP requires a residential or business street address, not a PO Box", 422);
  }
  if (!address.city || !address.country) {
    throw new KycError("ADDRESS_INVALID", "City and country are required", 422);
  }
  if (address.country === "US" && !/^\d{5}(-\d{4})?$/.test(address.postalCode ?? "")) {
    throw new KycError("ADDRESS_INVALID", "US postal code must be 12345 or 12345-6789", 422);
  }
  return { ...address, line1 };
}

export function validateDocument(document: Applicant["document"] | undefined, today: Date): Applicant["document"] {
  if (!document) {
    throw new KycError("DOCUMENT_REQUIRED", "A government-issued ID is required", 422);
  }
  if (!document.number || document.number.trim().length < 4 || document.number.length > 20) {
    throw new KycError("DOCUMENT_INVALID", "Document number must be 4-20 characters", 422);
  }
  if (!ISO_DATE.test(document.expiresOn ?? "")) {
    throw new KycError("DOCUMENT_INVALID", "expiresOn must be YYYY-MM-DD", 422);
  }
  if (document.expiresOn < today.toISOString().slice(0, 10)) {
    throw new KycError("DOCUMENT_EXPIRED", "Identification document has expired", 422);
  }
  if (document.type === "DRIVERS_LICENSE" || document.type === "STATE_ID") {
    if (document.issuingCountry !== "US") {
      throw new KycError("DOCUMENT_INVALID", "Only US-issued licenses and state IDs are accepted", 422);
    }
  }
  return document;
}
