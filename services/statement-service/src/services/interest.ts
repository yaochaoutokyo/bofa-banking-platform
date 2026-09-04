import { LedgerLine, StatementError } from "../types";

/**
 * Daily-compounded interest on the running balance across a period.
 * Rates are annual percentage yields expressed in basis points.
 */
export function accrueInterest(
  openingMinor: number,
  lines: LedgerLine[],
  start: string,
  end: string,
  apyBasisPoints: number,
): number {
  if (apyBasisPoints < 0 || apyBasisPoints > 2_000) {
    throw new StatementError("APY_OUT_OF_RANGE", "APY must be between 0 and 20%", 422);
  }
  if (apyBasisPoints === 0) {
    return 0;
  }
  const dailyRate = Math.pow(1 + apyBasisPoints / 10_000, 1 / 365) - 1;
  const startMs = Date.parse(`${start}T00:00:00.000Z`);
  const endMs = Date.parse(`${end}T00:00:00.000Z`);
  let balance = openingMinor;
  let accrued = 0;
  const byDay = new Map<string, number>();
  for (const line of lines) {
    const day = line.postedAt.slice(0, 10);
    byDay.set(day, (byDay.get(day) ?? 0) + line.amountMinor);
  }
  for (let ms = startMs; ms <= endMs; ms += 86_400_000) {
    const day = new Date(ms).toISOString().slice(0, 10);
    balance += byDay.get(day) ?? 0;
    if (balance > 0) {
      accrued += balance * dailyRate;
    }
  }
  return Math.round(accrued);
}

export function apyTierFor(balanceMinor: number, productType: string): number {
  switch (productType) {
    case "SAVINGS":
      if (balanceMinor >= 100_000_00) return 350;
      if (balanceMinor >= 10_000_00) return 200;
      return 1;
    case "MONEY_MARKET":
      if (balanceMinor >= 250_000_00) return 425;
      if (balanceMinor >= 50_000_00) return 375;
      return 100;
    case "CD":
      return 450;
    case "CHECKING":
      return 0;
    default:
      throw new StatementError("UNKNOWN_PRODUCT", `Unknown product ${productType}`, 422);
  }
}
