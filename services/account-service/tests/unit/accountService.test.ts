import { AccountError } from "../../src/domain";
import { MAX_ACCOUNTS_PER_CUSTOMER } from "../../src/services/accountService";
import { buildAccount, createHarness } from "../helpers/testHarness";

describe("AccountService.open", () => {
  it("opens a PENDING checking account with no deposit and publishes an event", async () => {
    const { service, events } = createHarness();

    const account = await service.open({ customerId: "CUST-1001", type: "CHECKING" });

    expect(account).toMatchObject({
      id: "ACC-0001",
      status: "PENDING",
      currency: "USD",
      balanceMinor: 0,
      openedAt: "2026-03-02T09:00:00.000Z",
    });
    expect(events.publish).toHaveBeenCalledWith(
      expect.objectContaining({ type: "account.opened", accountId: "ACC-0001" }),
    );
  });

  it("activates immediately when an initial deposit is supplied", async () => {
    const { service } = createHarness();
    const account = await service.open({ customerId: "CUST-1001", type: "SAVINGS", initialDepositMinor: 50_00 });
    expect(account.status).toBe("ACTIVE");
    expect(account.balanceMinor).toBe(50_00);
  });

  it.each([
    ["CD", 999_99, "MINIMUM_DEPOSIT"],
    ["MONEY_MARKET", 2_499_99, "MINIMUM_DEPOSIT"],
    ["CHECKING", -1, "NEGATIVE_DEPOSIT"],
  ] as const)("rejects %s with deposit %d (%s)", async (type, deposit, code) => {
    const { service, events } = createHarness();
    await expect(service.open({ customerId: "CUST-1001", type, initialDepositMinor: deposit })).rejects.toMatchObject({
      code,
      status: 422,
    });
    expect(events.publish).not.toHaveBeenCalled();
  });

  it("enforces the per-customer open account limit, ignoring closed accounts", async () => {
    const seed = Array.from({ length: MAX_ACCOUNTS_PER_CUSTOMER }, (_, i) =>
      buildAccount({ id: `SEED-${i}`, status: i === 0 ? "CLOSED" : "ACTIVE" }),
    );
    const { service } = createHarness(seed);

    await expect(service.open({ customerId: "CUST-1001", type: "CHECKING" })).resolves.toBeDefined();
    await expect(service.open({ customerId: "CUST-1001", type: "CHECKING" })).rejects.toMatchObject({
      code: "ACCOUNT_LIMIT",
      status: 409,
    });
  });
});

describe("AccountService.get / listForCustomer", () => {
  it("returns a copy of the stored account", async () => {
    const { service } = createHarness([buildAccount({ nickname: "Rainy day" })]);
    const account = await service.get("ACC-0001");
    expect(account.nickname).toBe("Rainy day");
  });

  it("throws ACCOUNT_NOT_FOUND for unknown ids", async () => {
    const { service } = createHarness();
    await expect(service.get("nope")).rejects.toBeInstanceOf(AccountError);
    await expect(service.get("nope")).rejects.toMatchObject({ status: 404 });
  });

  it("hides closed accounts unless asked", async () => {
    const { service } = createHarness([
      buildAccount({ id: "A", openedAt: "2026-01-01T00:00:00.000Z" }),
      buildAccount({ id: "B", status: "CLOSED", openedAt: "2026-01-02T00:00:00.000Z" }),
    ]);
    expect((await service.listForCustomer("CUST-1001")).map((a) => a.id)).toEqual(["A"]);
    expect((await service.listForCustomer("CUST-1001", true)).map((a) => a.id)).toEqual(["A", "B"]);
  });
});

describe("AccountService.update", () => {
  it("updates the nickname and bumps updatedAt", async () => {
    const { service } = createHarness([buildAccount()]);
    const updated = await service.update("ACC-0001", { nickname: "Vacation" });
    expect(updated.nickname).toBe("Vacation");
    expect(updated.updatedAt).toBe("2026-03-02T09:00:00.000Z");
  });

  it("refuses to modify a closed account", async () => {
    const { service } = createHarness([buildAccount({ status: "CLOSED" })]);
    await expect(service.update("ACC-0001", { nickname: "x" })).rejects.toMatchObject({ code: "ACCOUNT_CLOSED" });
  });
});

describe("AccountService.transition", () => {
  it.each([
    ["PENDING", "ACTIVATE", "ACTIVE"],
    ["ACTIVE", "FREEZE", "FROZEN"],
    ["FROZEN", "UNFREEZE", "ACTIVE"],
    ["DORMANT", "UNFREEZE", "ACTIVE"],
    ["ACTIVE", "CLOSE", "CLOSED"],
  ] as const)("%s --%s--> %s", async (from, action, to) => {
    const { service, events } = createHarness([buildAccount({ status: from })]);
    const result = await service.transition("ACC-0001", action, "customer request");
    expect(result.status).toBe(to);
    expect(events.publish).toHaveBeenCalledWith(
      expect.objectContaining({ type: `account.${to.toLowerCase()}`, payload: { reason: "customer request", from } }),
    );
  });

  it("sets closedAt when closing", async () => {
    const { service } = createHarness([buildAccount()]);
    const closed = await service.transition("ACC-0001", "CLOSE", "moving banks");
    expect(closed.closedAt).toBe("2026-03-02T09:00:00.000Z");
  });

  it.each([
    ["CLOSED", "ACTIVATE"],
    ["ACTIVE", "ACTIVATE"],
    ["PENDING", "FREEZE"],
  ] as const)("rejects invalid transition %s --%s-->", async (from, action) => {
    const { service } = createHarness([buildAccount({ status: from })]);
    await expect(service.transition("ACC-0001", action, "reason")).rejects.toMatchObject({
      code: "INVALID_TRANSITION",
      status: 409,
    });
  });

  it("refuses to close an account with a non-zero balance", async () => {
    const { service, events } = createHarness([buildAccount({ balanceMinor: 1 })]);
    await expect(service.transition("ACC-0001", "CLOSE", "reason")).rejects.toMatchObject({ code: "NON_ZERO_BALANCE" });
    expect(events.publish).not.toHaveBeenCalled();
  });
});

describe("AccountService.sweepDormant", () => {
  it("marks stale ACTIVE accounts dormant and leaves others alone", async () => {
    const { service, repo } = createHarness([
      buildAccount({ id: "OLD", updatedAt: "2024-01-01T00:00:00.000Z" }),
      buildAccount({ id: "NEW", updatedAt: "2026-02-01T00:00:00.000Z" }),
      buildAccount({ id: "FROZEN", status: "FROZEN", updatedAt: "2024-01-01T00:00:00.000Z" }),
    ]);

    const swept = await service.sweepDormant(new Date("2024-03-02T00:00:00.000Z"));

    expect(swept).toBe(1);
    expect((await repo.findById("OLD"))?.status).toBe("DORMANT");
    expect((await repo.findById("NEW"))?.status).toBe("ACTIVE");
    expect((await repo.findById("FROZEN"))?.status).toBe("FROZEN");
  });
});
