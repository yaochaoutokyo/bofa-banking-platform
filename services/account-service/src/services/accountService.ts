import {
  Account,
  AccountError,
  AccountStatus,
  AccountType,
  Clock,
  IdGenerator,
  OpenAccountInput,
  UpdateAccountInput,
} from "../domain";
import { AccountRepository } from "../repositories/accountRepository";

export const MAX_ACCOUNTS_PER_CUSTOMER = 10;
export const CD_MINIMUM_DEPOSIT_MINOR = 1_000_00;
export const MONEY_MARKET_MINIMUM_DEPOSIT_MINOR = 2_500_00;

/** Notifies downstream systems; injected so tests can assert on calls. */
export interface AccountEventPublisher {
  publish(event: { type: string; accountId: string; at: string; payload?: Record<string, unknown> }): Promise<void>;
}

const ALLOWED_TRANSITIONS: Record<string, AccountStatus> = {
  "PENDING:ACTIVATE": "ACTIVE",
  "ACTIVE:FREEZE": "FROZEN",
  "FROZEN:UNFREEZE": "ACTIVE",
  "DORMANT:UNFREEZE": "ACTIVE",
  "ACTIVE:CLOSE": "CLOSED",
  "FROZEN:CLOSE": "CLOSED",
  "DORMANT:CLOSE": "CLOSED",
  "PENDING:CLOSE": "CLOSED",
};

export class AccountService {
  constructor(
    private readonly repo: AccountRepository,
    private readonly events: AccountEventPublisher,
    private readonly clock: Clock,
    private readonly ids: IdGenerator,
  ) {}

  async open(input: OpenAccountInput): Promise<Account> {
    const existing = await this.repo.findByCustomer(input.customerId);
    const openCount = existing.filter((a) => a.status !== "CLOSED").length;
    if (openCount >= MAX_ACCOUNTS_PER_CUSTOMER) {
      throw new AccountError("ACCOUNT_LIMIT", `Customer already has ${MAX_ACCOUNTS_PER_CUSTOMER} open accounts`, 409);
    }

    const deposit = input.initialDepositMinor ?? 0;
    this.assertMinimumDeposit(input.type, deposit);

    const now = this.clock.now().toISOString();
    const account: Account = {
      id: this.ids.next(),
      customerId: input.customerId,
      type: input.type,
      status: deposit > 0 ? "ACTIVE" : "PENDING",
      currency: input.currency ?? "USD",
      balanceMinor: deposit,
      nickname: input.nickname,
      openedAt: now,
      updatedAt: now,
    };
    const saved = await this.repo.save(account);
    await this.events.publish({ type: "account.opened", accountId: saved.id, at: now, payload: { type: saved.type } });
    return saved;
  }

  async get(id: string): Promise<Account> {
    const account = await this.repo.findById(id);
    if (!account) {
      throw new AccountError("ACCOUNT_NOT_FOUND", `Account ${id} not found`, 404);
    }
    return account;
  }

  async listForCustomer(customerId: string, includeClosed = false): Promise<Account[]> {
    const accounts = await this.repo.findByCustomer(customerId);
    return includeClosed ? accounts : accounts.filter((a) => a.status !== "CLOSED");
  }

  async update(id: string, input: UpdateAccountInput): Promise<Account> {
    const account = await this.get(id);
    if (account.status === "CLOSED") {
      throw new AccountError("ACCOUNT_CLOSED", "Closed accounts cannot be modified", 409);
    }
    const updated: Account = {
      ...account,
      nickname: input.nickname ?? account.nickname,
      updatedAt: this.clock.now().toISOString(),
    };
    return this.repo.save(updated);
  }

  async transition(id: string, action: string, reason: string): Promise<Account> {
    const account = await this.get(id);
    const next = ALLOWED_TRANSITIONS[`${account.status}:${action}`];
    if (!next) {
      throw new AccountError(
        "INVALID_TRANSITION",
        `Cannot ${action} an account in status ${account.status}`,
        409,
      );
    }
    if (next === "CLOSED" && account.balanceMinor !== 0) {
      throw new AccountError("NON_ZERO_BALANCE", "Balance must be zero before closing", 409);
    }
    const now = this.clock.now().toISOString();
    const updated: Account = {
      ...account,
      status: next,
      updatedAt: now,
      closedAt: next === "CLOSED" ? now : account.closedAt,
    };
    const saved = await this.repo.save(updated);
    await this.events.publish({
      type: `account.${next.toLowerCase()}`,
      accountId: saved.id,
      at: now,
      payload: { reason, from: account.status },
    });
    return saved;
  }

  /** Marks accounts with no activity for 24 months as DORMANT (escheatment precursor). */
  async sweepDormant(inactiveSince: Date): Promise<number> {
    const cutoff = inactiveSince.toISOString();
    let swept = 0;
    for (const account of await this.repo.findAll()) {
      if (account.status === "ACTIVE" && account.updatedAt < cutoff) {
        await this.repo.save({ ...account, status: "DORMANT", updatedAt: this.clock.now().toISOString() });
        swept += 1;
      }
    }
    return swept;
  }

  private assertMinimumDeposit(type: AccountType, depositMinor: number): void {
    if (depositMinor < 0) {
      throw new AccountError("NEGATIVE_DEPOSIT", "Initial deposit cannot be negative", 422);
    }
    if (type === "CD" && depositMinor < CD_MINIMUM_DEPOSIT_MINOR) {
      throw new AccountError("MINIMUM_DEPOSIT", "CD accounts require a $1,000.00 minimum deposit", 422);
    }
    if (type === "MONEY_MARKET" && depositMinor < MONEY_MARKET_MINIMUM_DEPOSIT_MINOR) {
      throw new AccountError("MINIMUM_DEPOSIT", "Money market accounts require a $2,500.00 minimum deposit", 422);
    }
  }
}
