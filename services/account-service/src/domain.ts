export type AccountType = "CHECKING" | "SAVINGS" | "MONEY_MARKET" | "CD";
export type AccountStatus = "PENDING" | "ACTIVE" | "FROZEN" | "DORMANT" | "CLOSED";

export interface Account {
  id: string;
  customerId: string;
  type: AccountType;
  status: AccountStatus;
  currency: string;
  /** Balance in minor units (cents) to avoid floating-point drift. */
  balanceMinor: number;
  nickname?: string;
  openedAt: string;
  updatedAt: string;
  closedAt?: string;
}

export interface OpenAccountInput {
  customerId: string;
  type: AccountType;
  currency?: string;
  nickname?: string;
  initialDepositMinor?: number;
}

export interface UpdateAccountInput {
  nickname?: string;
}

export class AccountError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number = 400,
  ) {
    super(message);
    this.name = "AccountError";
  }
}

/** Abstraction over time so tests can freeze the clock. */
export interface Clock {
  now(): Date;
}

export const systemClock: Clock = { now: () => new Date() };

/** Abstraction over ID generation so tests get deterministic IDs. */
export interface IdGenerator {
  next(): string;
}
