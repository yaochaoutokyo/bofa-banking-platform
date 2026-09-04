export type IdDocumentType = "PASSPORT" | "DRIVERS_LICENSE" | "STATE_ID" | "PERMANENT_RESIDENT_CARD";

export interface Applicant {
  applicantId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string; // YYYY-MM-DD
  ssn?: string; // or ITIN
  countryOfCitizenship: string; // ISO-3166 alpha-2
  address: {
    line1: string;
    city: string;
    state: string;
    postalCode: string;
    country: string;
  };
  document: {
    type: IdDocumentType;
    number: string;
    issuingCountry: string;
    expiresOn: string; // YYYY-MM-DD
  };
  isPoliticallyExposed?: boolean;
  occupation?: string;
  expectedMonthlyActivityMinor?: number;
}

export type CipStatus = "PASS" | "REVIEW" | "FAIL";

export interface CipResult {
  applicantId: string;
  status: CipStatus;
  riskTier: "LOW" | "MEDIUM" | "HIGH" | "PROHIBITED";
  checks: CheckResult[];
  requiresEnhancedDueDiligence: boolean;
  evaluatedAt: string;
}

export interface CheckResult {
  name: string;
  passed: boolean;
  detail: string;
}

export interface BeneficialOwner {
  name: string;
  ownershipPercent: number;
  dateOfBirth: string;
  countryOfCitizenship: string;
}

export interface LegalEntity {
  entityId: string;
  legalName: string;
  ein: string;
  entityType: "LLC" | "CORP" | "PARTNERSHIP" | "SOLE_PROP" | "TRUST" | "NONPROFIT";
  formationCountry: string;
  beneficialOwners: BeneficialOwner[];
  controlPerson: string;
}

export class KycError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status = 400,
  ) {
    super(message);
  }
}
