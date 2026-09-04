import { Applicant, CheckResult, CipResult, KycError, LegalEntity } from "../types";
import { validateAddress, validateDateOfBirth, validateDocument, validateName, validateTaxId } from "../validation/identity";
import { COMPREHENSIVELY_SANCTIONED, HIGH_RISK_JURISDICTIONS, screenName } from "./sanctions";

export const EDD_ACTIVITY_THRESHOLD_MINOR = 100_000_00;
export const BENEFICIAL_OWNER_THRESHOLD_PERCENT = 25;

/**
 * Customer Identification Program decisioning (BSA/AML, 31 CFR 1020.220,
 * FinCEN CDD Rule 31 CFR 1010.230). No tests exist for this service.
 */
export class CipService {
  private readonly results = new Map<string, CipResult>();
  private readonly seenTaxIds = new Map<string, string>();

  constructor(private readonly clock: () => Date = () => new Date()) {}

  evaluate(applicant: Applicant): CipResult {
    const today = this.clock();
    const checks: CheckResult[] = [];

    const firstName = validateName(applicant.firstName, "firstName");
    const lastName = validateName(applicant.lastName, "lastName");
    validateDateOfBirth(applicant.dateOfBirth, today);
    const taxId = validateTaxId(applicant.ssn, applicant.countryOfCitizenship);
    validateAddress(applicant.address);
    validateDocument(applicant.document, today);
    checks.push({ name: "CIP_DATA_ELEMENTS", passed: true, detail: "Name, DOB, address, ID number present and well-formed" });

    if (taxId) {
      const previous = this.seenTaxIds.get(taxId);
      if (previous && previous !== applicant.applicantId) {
        checks.push({ name: "TAX_ID_UNIQUENESS", passed: false, detail: "Tax ID already associated with another applicant" });
      } else {
        this.seenTaxIds.set(taxId, applicant.applicantId);
        checks.push({ name: "TAX_ID_UNIQUENESS", passed: true, detail: "No prior applicant with this tax ID" });
      }
    }

    const citizenship = applicant.countryOfCitizenship.toUpperCase();
    if (COMPREHENSIVELY_SANCTIONED.has(citizenship) || COMPREHENSIVELY_SANCTIONED.has(applicant.address.country.toUpperCase())) {
      checks.push({ name: "JURISDICTION", passed: false, detail: "Comprehensively sanctioned jurisdiction" });
    } else if (HIGH_RISK_JURISDICTIONS.has(citizenship)) {
      checks.push({ name: "JURISDICTION", passed: true, detail: "High-risk jurisdiction; EDD required" });
    } else {
      checks.push({ name: "JURISDICTION", passed: true, detail: "Standard jurisdiction" });
    }

    const hits = screenName(`${firstName} ${lastName}`, applicant.dateOfBirth);
    if (hits.length > 0) {
      const strongest = hits[0];
      checks.push({
        name: "SANCTIONS_SCREENING",
        passed: strongest.score < 0.95,
        detail: `${hits.length} potential match(es); strongest ${strongest.matchedName} (${strongest.score}) [${strongest.program}]`,
      });
    } else {
      checks.push({ name: "SANCTIONS_SCREENING", passed: true, detail: "No list matches" });
    }

    if (applicant.isPoliticallyExposed) {
      checks.push({ name: "PEP", passed: true, detail: "Politically exposed person; senior management approval required" });
    }

    const expected = applicant.expectedMonthlyActivityMinor ?? 0;
    if (expected > EDD_ACTIVITY_THRESHOLD_MINOR) {
      checks.push({ name: "EXPECTED_ACTIVITY", passed: true, detail: "Expected activity above $100k/month; source-of-funds required" });
    }

    const result = this.decide(applicant.applicantId, checks, applicant, hits.length > 0, today);
    this.results.set(applicant.applicantId, result);
    return result;
  }

  private decide(applicantId: string, checks: CheckResult[], applicant: Applicant, hasHits: boolean, today: Date): CipResult {
    const failed = checks.filter((c) => !c.passed);
    const jurisdictionFail = failed.some((c) => c.name === "JURISDICTION");
    const sanctionsFail = failed.some((c) => c.name === "SANCTIONS_SCREENING");

    let riskTier: CipResult["riskTier"] = "LOW";
    if (jurisdictionFail || sanctionsFail) {
      riskTier = "PROHIBITED";
    } else if (
      hasHits ||
      applicant.isPoliticallyExposed ||
      HIGH_RISK_JURISDICTIONS.has(applicant.countryOfCitizenship.toUpperCase()) ||
      (applicant.expectedMonthlyActivityMinor ?? 0) > EDD_ACTIVITY_THRESHOLD_MINOR
    ) {
      riskTier = "HIGH";
    } else if (applicant.countryOfCitizenship.toUpperCase() !== "US" || failed.length > 0) {
      riskTier = "MEDIUM";
    }

    const status = riskTier === "PROHIBITED" ? "FAIL" : riskTier === "HIGH" || failed.length > 0 ? "REVIEW" : "PASS";
    return {
      applicantId,
      status,
      riskTier,
      checks,
      requiresEnhancedDueDiligence: riskTier === "HIGH",
      evaluatedAt: today.toISOString(),
    };
  }

  result(applicantId: string): CipResult {
    const found = this.results.get(applicantId);
    if (!found) {
      throw new KycError("RESULT_NOT_FOUND", "No CIP evaluation for this applicant", 404);
    }
    return found;
  }

  /** FinCEN CDD Rule: identify each 25%+ beneficial owner and one control person. */
  evaluateEntity(entity: LegalEntity): CipResult {
    const today = this.clock();
    const checks: CheckResult[] = [];
    if (!/^\d{2}-?\d{7}$/.test(entity.ein)) {
      throw new KycError("EIN_INVALID", "EIN must be 9 digits", 422);
    }
    if (!entity.controlPerson || entity.controlPerson.trim().length < 3) {
      throw new KycError("CONTROL_PERSON_REQUIRED", "A control person must be identified", 422);
    }
    const totalPercent = entity.beneficialOwners.reduce((s, o) => s + o.ownershipPercent, 0);
    if (totalPercent > 100) {
      throw new KycError("OWNERSHIP_EXCEEDS_100", "Beneficial ownership percentages exceed 100%", 422);
    }
    const significant = entity.beneficialOwners.filter((o) => o.ownershipPercent >= BENEFICIAL_OWNER_THRESHOLD_PERCENT);
    checks.push({
      name: "BENEFICIAL_OWNERSHIP",
      passed: entity.entityType === "SOLE_PROP" || entity.entityType === "TRUST" || significant.length > 0 || totalPercent < 25,
      detail: `${significant.length} owner(s) at or above ${BENEFICIAL_OWNER_THRESHOLD_PERCENT}%`,
    });
    let hasHits = false;
    for (const owner of significant) {
      const hits = screenName(owner.name, owner.dateOfBirth);
      if (hits.length > 0) {
        hasHits = true;
        checks.push({ name: "SANCTIONS_SCREENING", passed: hits[0].score < 0.95, detail: `Owner ${owner.name}: ${hits[0].matchedName}` });
      }
      if (COMPREHENSIVELY_SANCTIONED.has(owner.countryOfCitizenship.toUpperCase())) {
        checks.push({ name: "JURISDICTION", passed: false, detail: `Owner ${owner.name} in sanctioned jurisdiction` });
      }
    }
    if (!hasHits) {
      checks.push({ name: "SANCTIONS_SCREENING", passed: true, detail: "No owner list matches" });
    }
    const pseudoApplicant: Applicant = {
      applicantId: entity.entityId,
      firstName: entity.legalName,
      lastName: "",
      dateOfBirth: "",
      countryOfCitizenship: entity.formationCountry,
      address: { line1: "", city: "", state: "", postalCode: "", country: entity.formationCountry },
      document: { type: "PASSPORT", number: "", issuingCountry: "", expiresOn: "" },
    };
    const result = this.decide(entity.entityId, checks, pseudoApplicant, hasHits, today);
    this.results.set(entity.entityId, result);
    return result;
  }
}
