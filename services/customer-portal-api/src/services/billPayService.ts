import { randomUUID } from "node:crypto";
import { BillPayment, Payee, PortalError } from "../types";

export const MAX_PAYEES = 50;
export const DAILY_BILLPAY_LIMIT_MINOR = 50_000_00;

const FEDERAL_HOLIDAYS_2026 = new Set([
  "2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19", "2026-07-03",
  "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-12-25",
]);

export function isBusinessDay(isoDate: string): boolean {
  const day = new Date(`${isoDate}T00:00:00.000Z`).getUTCDay();
  return day !== 0 && day !== 6 && !FEDERAL_HOLIDAYS_2026.has(isoDate);
}

export function nextBusinessDay(isoDate: string): string {
  let cursor = isoDate;
  while (!isBusinessDay(cursor)) {
    const next = new Date(`${cursor}T00:00:00.000Z`);
    next.setUTCDate(next.getUTCDate() + 1);
    cursor = next.toISOString().slice(0, 10);
  }
  return cursor;
}

export class BillPayService {
  private readonly payees = new Map<string, Payee>();
  private readonly payments = new Map<string, BillPayment>();

  constructor(private readonly today: () => string = () => new Date().toISOString().slice(0, 10)) {}

  addPayee(customerId: string, name: string, accountNumber: string, category: Payee["category"]): Payee {
    const mine = this.listPayees(customerId);
    if (mine.length >= MAX_PAYEES) {
      throw new PortalError("PAYEE_LIMIT", `Maximum of ${MAX_PAYEES} payees reached`, 409);
    }
    const last4 = accountNumber.slice(-4);
    if (mine.some((p) => p.name.toLowerCase() === name.toLowerCase() && p.accountNumberLast4 === last4)) {
      throw new PortalError("PAYEE_DUPLICATE", "This payee already exists", 409);
    }
    const payee: Payee = {
      payeeId: `PAYEE-${randomUUID().slice(0, 8)}`,
      customerId,
      name: name.trim(),
      accountNumberLast4: last4,
      category,
      createdAt: new Date().toISOString(),
    };
    this.payees.set(payee.payeeId, payee);
    return payee;
  }

  listPayees(customerId: string): Payee[] {
    return [...this.payees.values()].filter((p) => p.customerId === customerId);
  }

  removePayee(customerId: string, payeeId: string): void {
    const payee = this.payees.get(payeeId);
    if (!payee || payee.customerId !== customerId) {
      throw new PortalError("PAYEE_NOT_FOUND", "Payee not found", 404);
    }
    const pending = [...this.payments.values()].some(
      (p) => p.payeeId === payeeId && (p.status === "SCHEDULED" || p.status === "PROCESSING"),
    );
    if (pending) {
      throw new PortalError("PAYEE_HAS_PENDING", "Cancel scheduled payments before removing the payee", 409);
    }
    this.payees.delete(payeeId);
  }

  schedule(
    customerId: string,
    input: { payeeId: string; fromAccountId: string; amountMinor: number; scheduledFor: string; memo?: string },
  ): BillPayment {
    const payee = this.payees.get(input.payeeId);
    if (!payee || payee.customerId !== customerId) {
      throw new PortalError("PAYEE_NOT_FOUND", "Payee not found", 404);
    }
    const today = this.today();
    if (input.scheduledFor < today) {
      throw new PortalError("DATE_IN_PAST", "Payment date must be today or later", 422);
    }
    const maxDate = new Date(`${today}T00:00:00.000Z`);
    maxDate.setUTCDate(maxDate.getUTCDate() + 365);
    if (input.scheduledFor > maxDate.toISOString().slice(0, 10)) {
      throw new PortalError("DATE_TOO_FAR", "Payments can be scheduled up to one year ahead", 422);
    }
    const effective = nextBusinessDay(input.scheduledFor);

    const sameDayTotal = [...this.payments.values()]
      .filter((p) => p.customerId === customerId && p.scheduledFor === effective && p.status !== "CANCELLED")
      .reduce((sum, p) => sum + p.amountMinor, 0);
    if (sameDayTotal + input.amountMinor > DAILY_BILLPAY_LIMIT_MINOR) {
      throw new PortalError("DAILY_LIMIT", "Daily bill pay limit of $50,000 exceeded", 422);
    }

    const payment: BillPayment = {
      paymentId: `BP-${randomUUID().slice(0, 8)}`,
      customerId,
      payeeId: input.payeeId,
      fromAccountId: input.fromAccountId,
      amountMinor: input.amountMinor,
      scheduledFor: effective,
      memo: input.memo,
      status: "SCHEDULED",
      createdAt: new Date().toISOString(),
    };
    this.payments.set(payment.paymentId, payment);
    return payment;
  }

  cancel(customerId: string, paymentId: string): BillPayment {
    const payment = this.payments.get(paymentId);
    if (!payment || payment.customerId !== customerId) {
      throw new PortalError("PAYMENT_NOT_FOUND", "Payment not found", 404);
    }
    if (payment.status !== "SCHEDULED") {
      throw new PortalError("PAYMENT_NOT_CANCELLABLE", `Cannot cancel a payment in status ${payment.status}`, 409);
    }
    if (payment.scheduledFor === this.today()) {
      throw new PortalError("PAYMENT_CUTOFF", "Same-day payments cannot be cancelled after cutoff", 409);
    }
    payment.status = "CANCELLED";
    return { ...payment };
  }

  listPayments(customerId: string, status?: BillPayment["status"]): BillPayment[] {
    return [...this.payments.values()]
      .filter((p) => p.customerId === customerId && (!status || p.status === status))
      .sort((a, b) => a.scheduledFor.localeCompare(b.scheduledFor));
  }

  /** Simulates the nightly processor: SCHEDULED payments due today move to PROCESSING then PAID. */
  runDailyBatch(): { processed: number; returned: number } {
    const today = this.today();
    let processed = 0;
    let returned = 0;
    for (const payment of this.payments.values()) {
      if (payment.status === "SCHEDULED" && payment.scheduledFor <= today) {
        payment.status = "PROCESSING";
        if (payment.amountMinor % 7_77 === 0) {
          payment.status = "RETURNED";
          returned += 1;
        } else {
          payment.status = "PAID";
          processed += 1;
        }
      }
    }
    return { processed, returned };
  }
}
