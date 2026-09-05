/**
 * Shared test harness for kyc-service.
 *
 * Conventions (mirrors services/account-service, the golden reference):
 *  - Time is deterministic via FrozenClock; pass `clock.fn` to CipService so
 *    no test depends on the wall clock.
 *  - Applicant IDs come from SequentialIds, never from randomness.
 *  - `buildApplicant` produces a fixture that passes every CIP data-element
 *    check; tests override only the fields relevant to the behaviour under
 *    test.
 */
import { Applicant } from "../../src/types";

export const TODAY = "2026-03-02";

export class FrozenClock {
  constructor(private current = new Date(`${TODAY}T09:00:00.000Z`)) {}

  now(): Date {
    return new Date(this.current);
  }

  readonly fn = (): Date => this.now();

  advanceDays(days: number): void {
    this.current = new Date(this.current.getTime() + days * 86_400_000);
  }
}

export class SequentialIds {
  private counter = 0;

  next(): string {
    this.counter += 1;
    return `APP-${String(this.counter).padStart(4, "0")}`;
  }
}

export function buildApplicant(overrides: Partial<Applicant> = {}): Applicant {
  return {
    applicantId: "APP-0001",
    firstName: "Maria",
    lastName: "O'Connor-Smith",
    dateOfBirth: "1985-06-15",
    ssn: "123-45-6789",
    countryOfCitizenship: "US",
    address: {
      line1: "100 Main Street",
      city: "Charlotte",
      state: "NC",
      postalCode: "28202",
      country: "US",
    },
    document: {
      type: "DRIVERS_LICENSE",
      number: "D1234567",
      issuingCountry: "US",
      expiresOn: "2030-01-01",
    },
    ...overrides,
  };
}
