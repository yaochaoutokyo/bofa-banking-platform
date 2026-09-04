import { Statement } from "../types";

export function formatMinor(amountMinor: number, currency = "USD"): string {
  const sign = amountMinor < 0 ? "-" : "";
  const abs = Math.abs(amountMinor);
  const dollars = Math.floor(abs / 100);
  const cents = String(abs % 100).padStart(2, "0");
  const grouped = dollars.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const symbol = currency === "USD" ? "$" : currency === "EUR" ? "€" : currency === "GBP" ? "£" : `${currency} `;
  return `${sign}${symbol}${grouped}.${cents}`;
}

export function renderText(statement: Statement): string {
  const width = 72;
  const rule = "-".repeat(width);
  const header = [
    "BANK OF AMERICA — ACCOUNT STATEMENT",
    `Account: ${maskAccount(statement.accountId)}`,
    `Period: ${statement.period.start} to ${statement.period.end}`,
    `Generated: ${statement.generatedAt}`,
    rule,
    `Opening balance ${formatMinor(statement.openingBalanceMinor).padStart(width - 16)}`,
    `Deposits        ${formatMinor(statement.totalDepositsMinor).padStart(width - 16)}`,
    `Withdrawals     ${formatMinor(-statement.totalWithdrawalsMinor).padStart(width - 16)}`,
    `Fees            ${formatMinor(-statement.totalFeesMinor).padStart(width - 16)}`,
    `Interest        ${formatMinor(statement.interestPaidMinor).padStart(width - 16)}`,
    `Closing balance ${formatMinor(statement.closingBalanceMinor).padStart(width - 16)}`,
    rule,
  ];
  const lines = statement.lines.map((l) => {
    const date = l.postedAt.slice(0, 10);
    const desc = l.description.length > 40 ? `${l.description.slice(0, 37)}...` : l.description.padEnd(40);
    return `${date}  ${desc} ${formatMinor(l.amountMinor).padStart(16)}`;
  });
  const footer = [rule, ...statement.disclosures.map((d) => `* ${d}`)];
  return [...header, ...lines, ...footer].join("\n");
}

export function renderCsv(statement: Statement): string {
  const escape = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  const rows = [
    ["posted_at", "description", "category", "amount"],
    ...statement.lines.map((l) => [l.postedAt, escape(l.description), l.category, (l.amountMinor / 100).toFixed(2)]),
  ];
  return rows.map((r) => r.join(",")).join("\n");
}

export function maskAccount(accountId: string): string {
  if (accountId.length <= 4) return "****";
  return `****${accountId.slice(-4)}`;
}
