import { LedgerLine } from "../types";

/** Simulated upstream ledger feed. Real deployments call ledger-service. */
export class InMemoryLedgerFeed {
  private readonly linesByAccount = new Map<string, LedgerLine[]>();

  constructor() {
    this.linesByAccount.set("ACC-1001", [
      { postedAt: "2026-01-15T10:00:00.000Z", description: "Opening deposit", amountMinor: 5_000_00, category: "DEPOSIT" },
      { postedAt: "2026-02-01T09:00:00.000Z", description: "Payroll ACME CORP", amountMinor: 3_250_00, category: "ACH" },
      { postedAt: "2026-02-03T14:12:00.000Z", description: "WHOLE FOODS #1234", amountMinor: -84_12, category: "CARD" },
      { postedAt: "2026-02-10T08:00:00.000Z", description: "Transfer to savings", amountMinor: -500_00, category: "TRANSFER" },
      { postedAt: "2026-02-15T00:00:00.000Z", description: "Monthly maintenance fee", amountMinor: -12_00, category: "FEE" },
      { postedAt: "2026-02-28T23:59:00.000Z", description: "Interest paid", amountMinor: 1_37, category: "INTEREST" },
      { postedAt: "2026-03-01T09:00:00.000Z", description: "Payroll ACME CORP", amountMinor: 3_250_00, category: "ACH" },
    ]);
    this.linesByAccount.set("ACC-2001", []);
  }

  async linesFor(accountId: string): Promise<LedgerLine[] | undefined> {
    const lines = this.linesByAccount.get(accountId);
    return lines ? lines.map((l) => ({ ...l })) : undefined;
  }

  async append(accountId: string, line: LedgerLine): Promise<void> {
    const existing = this.linesByAccount.get(accountId) ?? [];
    existing.push({ ...line });
    this.linesByAccount.set(accountId, existing);
  }
}
