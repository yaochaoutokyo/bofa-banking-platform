import { BillPayService } from "./billPayService";
import { CardControlService } from "./cardControlService";
import { ProfileService } from "./profileService";

export interface DashboardSummary {
  greeting: string;
  upcomingPayments: { payee: string; amount: string; date: string }[];
  cards: { cardId: string; locked: boolean }[];
  alerts: string[];
}

export function formatMoney(minor: number): string {
  const abs = Math.abs(minor);
  const s = `$${Math.floor(abs / 100).toLocaleString("en-US")}.${String(abs % 100).padStart(2, "0")}`;
  return minor < 0 ? `-${s}` : s;
}

export class DashboardService {
  constructor(
    private readonly profiles: ProfileService,
    private readonly billPay: BillPayService,
    private readonly cards: CardControlService,
  ) {}

  summary(customerId: string, now = new Date()): DashboardSummary {
    const profile = this.profiles.get(customerId);
    const hour = now.getUTCHours() - 5;
    const partOfDay = hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
    const greeting =
      profile.preferredLanguage === "es"
        ? `Buenos ${partOfDay === "morning" ? "días" : partOfDay === "afternoon" ? "tardes" : "noches"}, ${profile.firstName}`
        : `Good ${partOfDay}, ${profile.firstName}`;

    const payeesById = new Map(this.billPay.listPayees(customerId).map((p) => [p.payeeId, p.name]));
    const upcomingPayments = this.billPay
      .listPayments(customerId, "SCHEDULED")
      .slice(0, 5)
      .map((p) => ({ payee: payeesById.get(p.payeeId) ?? "Unknown payee", amount: formatMoney(p.amountMinor), date: p.scheduledFor }));

    const cards = this.cards.listForCustomer(customerId).map((c) => ({ cardId: c.cardId, locked: c.locked }));

    const alerts: string[] = [];
    if (!profile.paperless) {
      alerts.push("Go paperless to receive statements faster.");
    }
    if (cards.some((c) => c.locked)) {
      alerts.push("One or more of your cards is locked.");
    }
    if (profile.addresses.length === 0) {
      alerts.push("Add a home address to keep your profile complete.");
    }
    return { greeting, upcomingPayments, cards, alerts };
  }
}
