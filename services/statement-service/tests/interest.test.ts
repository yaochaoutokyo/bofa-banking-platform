import { accrueInterest, apyTierFor } from "../src/services/interest";

describe("apyTierFor", () => {
  it("returns tiered savings rates", () => {
    expect(apyTierFor(500_00, "SAVINGS")).toBe(1);
    expect(apyTierFor(10_000_00, "SAVINGS")).toBe(200);
    expect(apyTierFor(100_000_00, "SAVINGS")).toBe(350);
  });

  it("checking earns nothing", () => {
    expect(apyTierFor(1_000_000_00, "CHECKING")).toBe(0);
  });
});

describe("accrueInterest", () => {
  it("returns zero for a zero APY", () => {
    expect(accrueInterest(10_000_00, [], "2026-02-01", "2026-02-28", 0)).toBe(0);
  });

  it("accrues roughly APY/12 over a month on a flat balance", () => {
    const interest = accrueInterest(120_000_00, [], "2026-02-01", "2026-02-28", 200);
    // 2% APY on $120,000 ≈ $2,400/yr ≈ $184 for 28 days
    expect(interest).toBeGreaterThan(180_00);
    expect(interest).toBeLessThan(190_00);
  });
});
