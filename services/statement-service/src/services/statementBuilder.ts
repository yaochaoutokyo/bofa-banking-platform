import { LedgerLine, Statement, StatementError, StatementPeriod } from "../types";

export const MAX_LINES_PER_STATEMENT = 5_000;

export function parsePeriod(input: { start?: unknown; end?: unknown }): StatementPeriod {
  const iso = /^\d{4}-\d{2}-\d{2}$/;
  if (typeof input.start !== "string" || !iso.test(input.start)) {
    throw new StatementError("PERIOD_START_INVALID", "start must be YYYY-MM-DD", 422);
  }
  if (typeof input.end !== "string" || !iso.test(input.end)) {
    throw new StatementError("PERIOD_END_INVALID", "end must be YYYY-MM-DD", 422);
  }
  if (Number.isNaN(Date.parse(input.start)) || Number.isNaN(Date.parse(input.end))) {
    throw new StatementError("PERIOD_INVALID_DATE", "start/end must be real calendar dates", 422);
  }
  if (input.start > input.end) {
    throw new StatementError("PERIOD_INVERTED", "start must not be after end", 422);
  }
  const days = (Date.parse(input.end) - Date.parse(input.start)) / 86_400_000 + 1;
  if (days > 366) {
    throw new StatementError("PERIOD_TOO_LONG", "A statement period may not exceed one year", 422);
  }
  return { start: input.start, end: input.end };
}

export function linesInPeriod(lines: LedgerLine[], period: StatementPeriod): LedgerLine[] {
  const start = `${period.start}T00:00:00.000Z`;
  const end = `${period.end}T23:59:59.999Z`;
  return lines
    .filter((l) => l.postedAt >= start && l.postedAt <= end)
    .sort((a, b) => a.postedAt.localeCompare(b.postedAt));
}

export function openingBalance(lines: LedgerLine[], period: StatementPeriod): number {
  const start = `${period.start}T00:00:00.000Z`;
  return lines.filter((l) => l.postedAt < start).reduce((sum, l) => sum + l.amountMinor, 0);
}

export function buildStatement(
  accountId: string,
  allLines: LedgerLine[],
  period: StatementPeriod,
  now: Date,
  idFactory: () => string,
): Statement {
  if (!accountId) {
    throw new StatementError("ACCOUNT_REQUIRED", "accountId is required", 422);
  }
  const lines = linesInPeriod(allLines, period);
  if (lines.length > MAX_LINES_PER_STATEMENT) {
    throw new StatementError("TOO_MANY_LINES", "Statement exceeds maximum line count; split the period", 413);
  }
  for (const line of lines) {
    if (!Number.isInteger(line.amountMinor)) {
      throw new StatementError("AMOUNT_NOT_INTEGER", `Non-integer minor amount on ${line.postedAt}`, 500);
    }
  }
  const opening = openingBalance(allLines, period);
  let deposits = 0;
  let withdrawals = 0;
  let fees = 0;
  let interest = 0;
  for (const line of lines) {
    if (line.category === "FEE") {
      fees += -line.amountMinor;
    } else if (line.category === "INTEREST") {
      interest += line.amountMinor;
    } else if (line.amountMinor >= 0) {
      deposits += line.amountMinor;
    } else {
      withdrawals += -line.amountMinor;
    }
  }
  const closing = opening + deposits - withdrawals - fees + interest;

  return {
    statementId: idFactory(),
    accountId,
    period,
    openingBalanceMinor: opening,
    closingBalanceMinor: closing,
    totalDepositsMinor: deposits,
    totalWithdrawalsMinor: withdrawals,
    totalFeesMinor: fees,
    interestPaidMinor: interest,
    lines,
    generatedAt: now.toISOString(),
    disclosures: disclosuresFor(fees, interest, lines),
  };
}

function disclosuresFor(fees: number, interest: number, lines: LedgerLine[]): string[] {
  const out: string[] = [];
  if (fees > 0) {
    out.push("Fees are itemized above. See the Personal Schedule of Fees for details.");
  }
  if (interest > 0) {
    out.push("Interest is compounded daily and paid monthly (Truth in Savings Act, Reg DD).");
  }
  if (lines.some((l) => l.category === "ACH" || l.category === "CARD")) {
    out.push("Report unauthorized electronic transactions within 60 days (Regulation E).");
  }
  out.push("In case of errors or questions about your electronic transfers, call 800-432-1000.");
  return out;
}
