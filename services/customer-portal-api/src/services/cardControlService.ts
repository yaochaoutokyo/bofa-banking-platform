import { CardControls, PortalError } from "../types";

export class CardControlService {
  private readonly cards = new Map<string, CardControls>();
  private readonly lockHistory = new Map<string, string[]>();

  constructor() {
    this.cards.set("CARD-4421", {
      cardId: "CARD-4421",
      customerId: "CUST-1001",
      locked: false,
      internationalEnabled: false,
      onlineEnabled: true,
      atmDailyLimitMinor: 500_00,
      merchantBlocks: [],
      updatedAt: "2026-01-01T00:00:00.000Z",
    });
    this.cards.set("CARD-9910", {
      cardId: "CARD-9910",
      customerId: "CUST-1002",
      locked: true,
      internationalEnabled: true,
      onlineEnabled: true,
      atmDailyLimitMinor: 1_000_00,
      merchantBlocks: ["GAMBLING"],
      updatedAt: "2026-01-01T00:00:00.000Z",
    });
  }

  get(customerId: string, cardId: string): CardControls {
    const card = this.cards.get(cardId);
    if (!card || card.customerId !== customerId) {
      throw new PortalError("CARD_NOT_FOUND", "Card not found", 404);
    }
    return { ...card, merchantBlocks: [...card.merchantBlocks] };
  }

  listForCustomer(customerId: string): CardControls[] {
    return [...this.cards.values()].filter((c) => c.customerId === customerId).map((c) => ({ ...c }));
  }

  update(customerId: string, cardId: string, patch: Partial<Omit<CardControls, "cardId" | "customerId" | "updatedAt">>): CardControls {
    const current = this.get(customerId, cardId);
    if (patch.locked === false && current.locked) {
      const history = this.lockHistory.get(cardId) ?? [];
      const recentUnlocks = history.filter((ts) => Date.now() - Date.parse(ts) < 24 * 3_600_000).length;
      if (recentUnlocks >= 3) {
        throw new PortalError("UNLOCK_RATE_LIMIT", "Card unlocked too many times in 24h; contact support", 429);
      }
      history.push(new Date().toISOString());
      this.lockHistory.set(cardId, history);
    }
    if (patch.internationalEnabled && current.locked && patch.locked !== false) {
      throw new PortalError("CARD_LOCKED", "Unlock the card before enabling international use", 409);
    }
    if (patch.merchantBlocks) {
      patch.merchantBlocks = [...new Set(patch.merchantBlocks)].sort();
    }
    const updated: CardControls = { ...current, ...patch, updatedAt: new Date().toISOString() };
    this.cards.set(cardId, updated);
    return this.get(customerId, cardId);
  }

  reportLostOrStolen(customerId: string, cardId: string, reason: "LOST" | "STOLEN"): { replacementCardId: string } {
    const card = this.get(customerId, cardId);
    this.cards.set(cardId, { ...card, locked: true, onlineEnabled: false, internationalEnabled: false, updatedAt: new Date().toISOString() });
    const replacementCardId = `CARD-${String(Math.floor(Math.random() * 9000) + 1000)}`;
    this.cards.set(replacementCardId, {
      ...card,
      cardId: replacementCardId,
      locked: reason === "STOLEN",
      merchantBlocks: [...card.merchantBlocks],
      updatedAt: new Date().toISOString(),
    });
    return { replacementCardId };
  }
}
