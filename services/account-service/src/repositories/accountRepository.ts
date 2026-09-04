import { Account } from "../domain";

/**
 * Repository interface. The service depends on this interface, never on a
 * concrete store, so tests can inject an in-memory or mocked implementation.
 */
export interface AccountRepository {
  findById(id: string): Promise<Account | undefined>;
  findByCustomer(customerId: string): Promise<Account[]>;
  findAll(): Promise<Account[]>;
  save(account: Account): Promise<Account>;
}

export class InMemoryAccountRepository implements AccountRepository {
  private readonly accounts = new Map<string, Account>();

  constructor(seed: Account[] = []) {
    for (const account of seed) {
      this.accounts.set(account.id, { ...account });
    }
  }

  async findById(id: string): Promise<Account | undefined> {
    const found = this.accounts.get(id);
    return found ? { ...found } : undefined;
  }

  async findByCustomer(customerId: string): Promise<Account[]> {
    return [...this.accounts.values()]
      .filter((a) => a.customerId === customerId)
      .map((a) => ({ ...a }))
      .sort((a, b) => a.openedAt.localeCompare(b.openedAt));
  }

  async findAll(): Promise<Account[]> {
    return [...this.accounts.values()].map((a) => ({ ...a }));
  }

  async save(account: Account): Promise<Account> {
    this.accounts.set(account.id, { ...account });
    return { ...account };
  }
}
