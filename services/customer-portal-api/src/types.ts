export interface CustomerProfile {
  customerId: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  preferredLanguage: "en" | "es";
  paperless: boolean;
  addresses: Address[];
  updatedAt: string;
}

export interface Address {
  type: "HOME" | "MAILING";
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
}

export interface Payee {
  payeeId: string;
  customerId: string;
  name: string;
  accountNumberLast4: string;
  category: "UTILITY" | "CREDIT_CARD" | "MORTGAGE" | "PERSON" | "OTHER";
  createdAt: string;
}

export interface BillPayment {
  paymentId: string;
  customerId: string;
  payeeId: string;
  fromAccountId: string;
  amountMinor: number;
  scheduledFor: string; // YYYY-MM-DD
  memo?: string;
  status: "SCHEDULED" | "PROCESSING" | "PAID" | "CANCELLED" | "RETURNED";
  createdAt: string;
}

export interface CardControls {
  cardId: string;
  customerId: string;
  locked: boolean;
  internationalEnabled: boolean;
  onlineEnabled: boolean;
  atmDailyLimitMinor: number;
  merchantBlocks: string[];
  updatedAt: string;
}

export class PortalError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status = 400,
  ) {
    super(message);
  }
}
