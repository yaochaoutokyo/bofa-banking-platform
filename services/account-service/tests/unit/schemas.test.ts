import { formatZodIssues, openAccountSchema, statusTransitionSchema, updateAccountSchema } from "../../src/validation/schemas";

describe("openAccountSchema", () => {
  it("applies USD as the default currency", () => {
    const parsed = openAccountSchema.parse({ customerId: "CUST-1001", type: "CHECKING" });
    expect(parsed.currency).toBe("USD");
  });

  it.each([
    [{ customerId: "1001", type: "CHECKING" }, "customerId"],
    [{ customerId: "CUST-1001", type: "CRYPTO" }, "type"],
    [{ customerId: "CUST-1001", type: "CHECKING", currency: "JPY" }, "currency"],
    [{ customerId: "CUST-1001", type: "CHECKING", initialDepositMinor: 10.5 }, "initialDepositMinor"],
    [{ customerId: "CUST-1001", type: "CHECKING", nickname: "" }, "nickname"],
  ])("rejects %j on field %s", (body, field) => {
    const result = openAccountSchema.safeParse(body);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(formatZodIssues(result.error).map((i) => i.field)).toContain(field);
    }
  });
});

describe("updateAccountSchema", () => {
  it("requires at least one field", () => {
    expect(updateAccountSchema.safeParse({}).success).toBe(false);
    expect(updateAccountSchema.safeParse({ nickname: " ok " }).success).toBe(true);
  });
});

describe("statusTransitionSchema", () => {
  it("requires a reason of at least 3 characters", () => {
    expect(statusTransitionSchema.safeParse({ action: "FREEZE", reason: "no" }).success).toBe(false);
    expect(statusTransitionSchema.safeParse({ action: "FREEZE", reason: "fraud" }).success).toBe(true);
  });
});
