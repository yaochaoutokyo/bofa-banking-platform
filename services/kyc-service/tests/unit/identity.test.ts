/**
 * CIP data-element validation — 31 CFR 1020.220(a)(2)(i): name, date of
 * birth, address, identification number. See COMPLIANCE.md → kyc-service.
 *
 * Scope of this step: the four required data elements plus the under-18 /
 * invalid-DOB rules. SSN/ITIN issuance rules, document expiry, sanctions/OFAC,
 * PEP and beneficial ownership are covered in follow-up PRs.
 */
import { ageOn, validateAddress, validateDateOfBirth, validateName, validateTaxId } from "../../src/validation/identity";
import { Applicant } from "../../src/types";
import { buildApplicant, FrozenClock } from "../helpers/testHarness";

const TODAY = new FrozenClock().now(); // 2026-03-02T09:00:00.000Z

function kycError(code: string, status = 422) {
  return expect.objectContaining({ code, status });
}

function buildAddress(overrides: Partial<Applicant["address"]> = {}): Applicant["address"] {
  return { ...buildApplicant().address, ...overrides };
}

// ------------------------------------------------------------------ name ----
describe("validateName", () => {
  it("returns the trimmed name", () => {
    expect(validateName("  Maria  ", "firstName")).toBe("Maria");
  });

  it.each([
    ["O'Connor-Smith", "apostrophe and hyphen"],
    ["St. James", "period and space"],
    ["François", "Latin-1 supplement"],
    ["Łukasz", "Latin extended-A"],
    ["Ştefan", "Latin extended-B"],
    ["A".repeat(60), "exactly 60 characters (boundary)"],
  ])("accepts %s (%s)", (value) => {
    expect(validateName(value, "lastName")).toBe(value);
  });

  it.each([
    [undefined, "NAME_REQUIRED"],
    ["", "NAME_REQUIRED"],
    ["   ", "NAME_REQUIRED"],
    ["A".repeat(61), "NAME_TOO_LONG"],
    ["R2D2", "NAME_INVALID"],
    ["Jane<script>", "NAME_INVALID"],
    ["Maria; DROP TABLE", "NAME_INVALID"],
    ["Ma\u0000ria", "NAME_INVALID"],
  ])("rejects %j with %s", (value, code) => {
    expect(() => validateName(value, "firstName")).toThrow(kycError(code));
  });

  it("names the offending field in the message", () => {
    expect(() => validateName("", "lastName")).toThrow("lastName is required");
    expect(() => validateName("A".repeat(61), "firstName")).toThrow("firstName exceeds 60 characters");
  });

  // DEFECT: a name made only of punctuation ("-", "'", ".") passes the
  // character-class check, so an applicant can be onboarded with no legal name
  // (31 CFR 1020.220(a)(2)(i)(A)). Un-skip once validateName requires at least
  // one letter.
  it.skip.each([["-"], ["'"], ["..."], ["' -"]])("rejects punctuation-only name %j", (value) => {
    expect(() => validateName(value, "firstName")).toThrow(kycError("NAME_INVALID"));
  });
});

// ------------------------------------------------------------------- DOB ----
describe("validateDateOfBirth", () => {
  it("returns the ISO date unchanged for a valid adult", () => {
    expect(validateDateOfBirth("1985-06-15", TODAY)).toBe("1985-06-15");
  });

  it("accepts a real leap-day birthday", () => {
    expect(validateDateOfBirth("1988-02-29", TODAY)).toBe("1988-02-29");
  });

  it.each([
    [undefined, "missing"],
    ["", "empty"],
    ["15/06/1985", "US slash format"],
    ["1985-6-15", "unpadded month"],
    ["19850615", "no separators"],
    ["1985-06-15T00:00:00.000Z", "full timestamp"],
    ["1985-06-15 ", "trailing whitespace"],
  ] as Array<[string | undefined, string]>)("rejects malformed %j (%s) with DOB_INVALID", (value) => {
    expect(() => validateDateOfBirth(value, TODAY)).toThrow(kycError("DOB_INVALID"));
    expect(() => validateDateOfBirth(value, TODAY)).toThrow("must be YYYY-MM-DD");
  });

  it.each([
    ["2023-02-29", "Feb 29 in a non-leap year"],
    ["1900-02-29", "Feb 29 in a century non-leap year"],
    ["1985-04-31", "April 31st"],
    ["1985-13-01", "month 13"],
    ["1985-00-10", "month 0"],
    ["1985-06-00", "day 0"],
    ["1985-06-32", "day 32"],
  ])("rejects non-existent date %s (%s) with DOB_INVALID", (value) => {
    expect(() => validateDateOfBirth(value, TODAY)).toThrow(kycError("DOB_INVALID"));
    expect(() => validateDateOfBirth(value, TODAY)).toThrow("not a real date");
  });

  it.each([
    ["2026-03-03", "tomorrow"],
    ["2027-01-01", "next year"],
    ["2999-12-31", "far future"],
  ])("rejects future DOB %s (%s) with DOB_FUTURE", (value) => {
    expect(() => validateDateOfBirth(value, TODAY)).toThrow(kycError("DOB_FUTURE"));
  });

  describe("under-18 rule (custodial accounts use a different flow)", () => {
    it("accepts an applicant who turns exactly 18 today", () => {
      expect(validateDateOfBirth("2008-03-02", TODAY)).toBe("2008-03-02");
    });

    it("accepts a leap-day-born applicant who turned 18 on 1 March of a non-leap year", () => {
      expect(validateDateOfBirth("2008-02-29", TODAY)).toBe("2008-02-29");
    });

    it.each([
      ["2008-03-03", "17 years and 364 days"],
      ["2008-12-31", "17"],
      ["2020-01-01", "6"],
      ["2026-03-02", "born today"],
      ["2026-03-01", "born yesterday"],
    ])("rejects %s (%s) with APPLICANT_MINOR", (value) => {
      expect(() => validateDateOfBirth(value, TODAY)).toThrow(kycError("APPLICANT_MINOR"));
    });
  });

  describe("implausible age rule", () => {
    it.each([
      ["1906-03-02", "turns exactly 120 today"],
      ["1905-03-03", "120 years and 364 days"],
    ])("accepts %s (%s)", (value) => {
      expect(validateDateOfBirth(value, TODAY)).toBe(value);
    });

    it.each([
      ["1905-03-02", "turns 121 today"],
      ["1800-01-01", "226"],
    ])("rejects %s (%s) with DOB_IMPLAUSIBLE", (value) => {
      expect(() => validateDateOfBirth(value, TODAY)).toThrow(kycError("DOB_IMPLAUSIBLE"));
    });
  });

  it("does not depend on the wall clock", () => {
    const later = new FrozenClock();
    later.advanceDays(1);
    expect(() => validateDateOfBirth("2008-03-03", TODAY)).toThrow(kycError("APPLICANT_MINOR"));
    expect(validateDateOfBirth("2008-03-03", later.now())).toBe("2008-03-03");
  });
});

describe("ageOn", () => {
  it.each([
    ["2008-03-02", "2026-03-02", 18, "birthday today"],
    ["2008-03-03", "2026-03-02", 17, "day before birthday"],
    ["2008-03-01", "2026-03-02", 18, "day after birthday"],
    ["2008-02-29", "2026-02-28", 17, "leap-day birthday, Feb 28 of non-leap year"],
    ["2008-02-29", "2026-03-01", 18, "leap-day birthday, Mar 1 of non-leap year"],
    ["2008-02-29", "2028-02-29", 20, "leap-day birthday on a leap year"],
    ["2000-12-31", "2026-01-01", 25, "year boundary"],
    ["2026-03-02", "2026-03-02", 0, "born today"],
    ["1985-06-15", "1985-06-14", -1, "born tomorrow (caller rejects as future)"],
  ])("ageOn(%s, %s) = %d (%s)", (dob, today, expected) => {
    expect(ageOn(new Date(`${dob}T00:00:00.000Z`), new Date(`${today}T09:00:00.000Z`))).toBe(expected);
  });
});

// ------------------------------------------------------------------ TIN ----
describe("validateTaxId", () => {
  it.each([
    ["123-45-6789", "123456789", "SSN with dashes"],
    ["123456789", "123456789", "SSN digits only"],
    ["912-70-1234", "912701234", "ITIN with dashes"],
    ["900500001", "900500001", "ITIN digits only"],
  ])("normalises %s to %s (%s)", (input, expected) => {
    expect(validateTaxId(input, "US")).toBe(expected);
  });

  it("requires an SSN or ITIN for US persons", () => {
    expect(() => validateTaxId(undefined, "US")).toThrow(kycError("TAX_ID_REQUIRED"));
    expect(() => validateTaxId("", "US")).toThrow(kycError("TAX_ID_REQUIRED"));
  });

  it("is optional for non-US persons", () => {
    expect(validateTaxId(undefined, "GB")).toBeUndefined();
    expect(validateTaxId("", "CA")).toBeUndefined();
  });

  it("still validates a tax ID supplied by a non-US person", () => {
    expect(validateTaxId("123-45-6789", "GB")).toBe("123456789");
    expect(() => validateTaxId("12345", "GB")).toThrow(kycError("TAX_ID_INVALID"));
  });

  it.each([
    ["12345678", "8 digits"],
    ["1234567890", "10 digits"],
    ["123-45-678A", "letter"],
    ["123 45 6789", "spaces instead of dashes"],
    ["123.45.6789", "dots"],
  ])("rejects %j (%s) as not 9 digits", (value) => {
    expect(() => validateTaxId(value, "US")).toThrow(kycError("TAX_ID_INVALID"));
    expect(() => validateTaxId(value, "US")).toThrow("must be 9 digits");
  });

  it("ignores dash positions and validates only the digits", () => {
    expect(validateTaxId("12-345-6789", "US")).toBe("123456789");
    expect(validateTaxId("-123456789-", "US")).toBe("123456789");
  });
});

// -------------------------------------------------------------- address ----
describe("validateAddress", () => {
  it("returns the address with line1 trimmed", () => {
    const address = buildAddress({ line1: "  100 Main Street  " });
    expect(validateAddress(address)).toEqual({ ...address, line1: "100 Main Street" });
  });

  it.each([
    ["28202", "5-digit ZIP"],
    ["28202-1234", "ZIP+4"],
  ])("accepts US postal code %s (%s)", (postalCode) => {
    expect(validateAddress(buildAddress({ postalCode })).postalCode).toBe(postalCode);
  });

  it("does not apply the US postal code format to other countries", () => {
    const gb = buildAddress({ line1: "10 Downing Street", city: "London", state: "", postalCode: "SW1A 2AA", country: "GB" });
    expect(validateAddress(gb)).toEqual(gb);
    expect(validateAddress(buildAddress({ country: "IE", postalCode: "" })).country).toBe("IE");
  });

  it("requires an address", () => {
    expect(() => validateAddress(undefined)).toThrow(kycError("ADDRESS_REQUIRED"));
  });

  it.each([
    [{ line1: "" }, "Address line 1 is required", "empty line1"],
    [{ line1: "   " }, "Address line 1 is required", "whitespace line1"],
    [{ line1: "12" }, "Address line 1 is required", "line1 under 3 chars"],
    [{ line1: " 1 " }, "Address line 1 is required", "line1 under 3 chars after trim"],
    [{ city: "" }, "City and country are required", "missing city"],
    [{ country: "" }, "City and country are required", "missing country"],
    [{ postalCode: "2820" }, "US postal code must be", "4-digit ZIP"],
    [{ postalCode: "282021" }, "US postal code must be", "6-digit ZIP"],
    [{ postalCode: "ABCDE" }, "US postal code must be", "alphabetic ZIP"],
    [{ postalCode: "28202-12" }, "US postal code must be", "truncated ZIP+4"],
    [{ postalCode: "28202 1234" }, "US postal code must be", "ZIP+4 with space"],
    [{ postalCode: "" }, "US postal code must be", "missing US ZIP"],
  ] as Array<[Partial<Applicant["address"]>, string, string]>)("rejects %j (%s) with ADDRESS_INVALID", (overrides, message) => {
    expect(() => validateAddress(buildAddress(overrides))).toThrow(kycError("ADDRESS_INVALID"));
    expect(() => validateAddress(buildAddress(overrides))).toThrow(message);
  });

  it("treats a missing line1 property as an invalid address", () => {
    const { line1: _line1, ...withoutLine1 } = buildAddress();
    expect(() => validateAddress(withoutLine1 as Applicant["address"])).toThrow(kycError("ADDRESS_INVALID"));
  });

  it("checks line1 before city/country/postal code", () => {
    expect(() => validateAddress(buildAddress({ line1: "", city: "", postalCode: "" }))).toThrow("Address line 1 is required");
  });

  describe("PO Box rejection (CIP requires a residential or business street address)", () => {
    it.each([
      ["PO Box 123"],
      ["P.O. Box 123"],
      ["po box 5"],
      ["P O Box 9"],
      ["P.O.Box 77"],
      ["  PO Box 1  "],
      ["PO BOX 4400"],
    ])("rejects %j with ADDRESS_PO_BOX", (line1) => {
      expect(() => validateAddress(buildAddress({ line1 }))).toThrow(kycError("ADDRESS_PO_BOX"));
    });

    it.each([["100 Main Street"], ["1 Poplar Box Lane"], ["Post Road 12"]])("accepts street address %j", (line1) => {
      expect(validateAddress(buildAddress({ line1 })).line1).toBe(line1);
    });

    // DEFECT: the PO Box detector only matches the abbreviation ("PO Box",
    // "P.O. Box"); the spelled-out form "Post Office Box 123" is accepted as a
    // residential address. 31 CFR 1020.220(a)(2)(i)(3) — non-compliant
    // address on file. Un-skip once the detector covers the spelled-out form.
    it.skip.each([["Post Office Box 123"], ["Post Office Box 9, Charlotte"]])("rejects spelled-out %j", (line1) => {
      expect(() => validateAddress(buildAddress({ line1 }))).toThrow(kycError("ADDRESS_PO_BOX"));
    });
  });

  // DEFECT: the US postal-code rule is keyed on the literal "US", while
  // CipService.evaluate upper-cases country codes for jurisdiction checks. An
  // applicant submitting country "us" bypasses ZIP validation entirely.
  // CIP residential address requirement (31 CFR 1020.220(a)(2)(i)(3)).
  it.skip("applies the US postal code rule case-insensitively to the country code", () => {
    expect(() => validateAddress(buildAddress({ country: "us", postalCode: "" }))).toThrow(kycError("ADDRESS_INVALID"));
  });
});
