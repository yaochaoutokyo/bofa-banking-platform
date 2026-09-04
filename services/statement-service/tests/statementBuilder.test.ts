import { buildStatement, parsePeriod } from "../src/services/statementBuilder";
import { LedgerLine } from "../src/types";

const lines: LedgerLine[] = [
  { postedAt: "2026-01-15T10:00:00.000Z", description: "Opening deposit", amountMinor: 5_000_00, category: "DEPOSIT" },
  { postedAt: "2026-02-01T09:00:00.000Z", description: "Payroll", amountMinor: 3_000_00, category: "ACH" },
  { postedAt: "2026-02-03T14:12:00.000Z", description: "Groceries", amountMinor: -84_12, category: "CARD" },
  { postedAt: "2026-02-15T00:00:00.000Z", description: "Fee", amountMinor: -12_00, category: "FEE" },
  { postedAt: "2026-02-28T23:59:00.000Z", description: "Interest", amountMinor: 1_37, category: "INTEREST" },
  { postedAt: "2026-03-01T09:00:00.000Z", description: "Payroll", amountMinor: 3_000_00, category: "ACH" },
];

describe("parsePeriod", () => {
  it("accepts a valid period", () => {
    expect(parsePeriod({ start: "2026-02-01", end: "2026-02-28" })).toEqual({ start: "2026-02-01", end: "2026-02-28" });
  });

  it("rejects inverted periods", () => {
    expect(() => parsePeriod({ start: "2026-02-28", end: "2026-02-01" })).toThrow(/start must not be after end/);
  });

  it("rejects malformed dates", () => {
    expect(() => parsePeriod({ start: "02/01/2026", end: "2026-02-28" })).toThrow(/YYYY-MM-DD/);
  });
});

describe("buildStatement", () => {
  it("computes opening/closing balances and category totals for February", () => {
    const statement = buildStatement(
      "ACC-1001",
      lines,
      { start: "2026-02-01", end: "2026-02-28" },
      new Date("2026-03-01T00:00:00.000Z"),
      () => "STMT-1",
    );
    expect(statement.openingBalanceMinor).toBe(5_000_00);
    expect(statement.totalDepositsMinor).toBe(3_000_00);
    expect(statement.totalWithdrawalsMinor).toBe(84_12);
    expect(statement.totalFeesMinor).toBe(12_00);
    expect(statement.interestPaidMinor).toBe(1_37);
    expect(statement.closingBalanceMinor).toBe(5_000_00 + 3_000_00 - 84_12 - 12_00 + 1_37);
    expect(statement.lines).toHaveLength(4);
    expect(statement.disclosures).toEqual(
      expect.arrayContaining([expect.stringContaining("Regulation E"), expect.stringContaining("Reg DD")]),
    );
  });

  it("produces an empty statement when no lines fall in the period", () => {
    const statement = buildStatement("ACC-1001", lines, { start: "2025-01-01", end: "2025-01-31" }, new Date(), () => "S");
    expect(statement.lines).toHaveLength(0);
    expect(statement.openingBalanceMinor).toBe(0);
    expect(statement.closingBalanceMinor).toBe(0);
  });
});
