export interface LedgerLine {
  postedAt: string; // ISO date-time
  description: string;
  /** Signed amount in minor units; negative = debit. */
  amountMinor: number;
  category: "DEPOSIT" | "WITHDRAWAL" | "FEE" | "INTEREST" | "TRANSFER" | "CARD" | "ACH" | "WIRE";
}

export interface StatementPeriod {
  start: string; // inclusive ISO date (YYYY-MM-DD)
  end: string; // inclusive ISO date
}

export interface Statement {
  statementId: string;
  accountId: string;
  period: StatementPeriod;
  openingBalanceMinor: number;
  closingBalanceMinor: number;
  totalDepositsMinor: number;
  totalWithdrawalsMinor: number;
  totalFeesMinor: number;
  interestPaidMinor: number;
  lines: LedgerLine[];
  generatedAt: string;
  /** Reg DD / Truth in Savings disclosures. */
  disclosures: string[];
}

export class StatementError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status = 400,
  ) {
    super(message);
  }
}
