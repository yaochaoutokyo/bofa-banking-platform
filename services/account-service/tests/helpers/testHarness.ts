/**
 * Shared test harness for account-service.
 *
 * Conventions (see README "Testing conventions"):
 *  - Every collaborator the service depends on is injected; nothing is
 *    imported as a module-level singleton.
 *  - Time and IDs are deterministic via FrozenClock / SequentialIds.
 *  - Side-effecting collaborators (event publisher) are jest mocks so tests
 *    can assert on interactions.
 *  - `buildAccount` produces a valid fixture with sensible defaults; tests
 *    override only the fields relevant to the behaviour under test.
 */
import { Account, Clock, IdGenerator } from "../../src/domain";
import { InMemoryAccountRepository } from "../../src/repositories/accountRepository";
import { AccountEventPublisher, AccountService } from "../../src/services/accountService";

export class FrozenClock implements Clock {
  constructor(private current = new Date("2026-03-02T09:00:00.000Z")) {}

  now(): Date {
    return new Date(this.current);
  }

  advanceDays(days: number): void {
    this.current = new Date(this.current.getTime() + days * 86_400_000);
  }
}

export class SequentialIds implements IdGenerator {
  private counter = 0;

  next(): string {
    this.counter += 1;
    return `ACC-${String(this.counter).padStart(4, "0")}`;
  }
}

export function buildAccount(overrides: Partial<Account> = {}): Account {
  return {
    id: "ACC-0001",
    customerId: "CUST-1001",
    type: "CHECKING",
    status: "ACTIVE",
    currency: "USD",
    balanceMinor: 0,
    openedAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z",
    ...overrides,
  };
}

export interface Harness {
  service: AccountService;
  repo: InMemoryAccountRepository;
  events: jest.Mocked<AccountEventPublisher>;
  clock: FrozenClock;
}

export function createHarness(seed: Account[] = []): Harness {
  const repo = new InMemoryAccountRepository(seed);
  const events: jest.Mocked<AccountEventPublisher> = { publish: jest.fn().mockResolvedValue(undefined) };
  const clock = new FrozenClock();
  const service = new AccountService(repo, events, clock, new SequentialIds());
  return { service, repo, events, clock };
}
