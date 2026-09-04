import { ProfileService } from "../src/services/profileService";

describe("ProfileService", () => {
  it("returns the seeded profile", () => {
    const service = new ProfileService();
    const profile = service.get("CUST-1001");
    expect(profile.firstName).toBe("Jane");
    expect(profile.addresses).toHaveLength(1);
  });

  it("updates paperless preference", () => {
    const service = new ProfileService();
    const updated = service.update("CUST-1002", { paperless: true });
    expect(updated.paperless).toBe(true);
  });
});
