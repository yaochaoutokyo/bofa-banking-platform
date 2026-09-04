import { CustomerProfile, PortalError } from "../types";

export class ProfileService {
  private readonly profiles = new Map<string, CustomerProfile>();
  private readonly emailIndex = new Map<string, string>();

  constructor() {
    this.seed({
      customerId: "CUST-1001",
      firstName: "Jane",
      lastName: "Doe",
      email: "jane.doe@example.com",
      phone: "+12125550100",
      preferredLanguage: "en",
      paperless: true,
      addresses: [{ type: "HOME", line1: "100 N Tryon St", city: "Charlotte", state: "NC", postalCode: "28255" }],
      updatedAt: "2026-01-10T00:00:00.000Z",
    });
    this.seed({
      customerId: "CUST-1002",
      firstName: "Luis",
      lastName: "Ortega",
      email: "luis.ortega@example.com",
      phone: "+13055550199",
      preferredLanguage: "es",
      paperless: false,
      addresses: [],
      updatedAt: "2026-01-12T00:00:00.000Z",
    });
  }

  private seed(profile: CustomerProfile): void {
    this.profiles.set(profile.customerId, profile);
    this.emailIndex.set(profile.email.toLowerCase(), profile.customerId);
  }

  get(customerId: string): CustomerProfile {
    const profile = this.profiles.get(customerId);
    if (!profile) {
      throw new PortalError("PROFILE_NOT_FOUND", `No profile for ${customerId}`, 404);
    }
    return { ...profile, addresses: profile.addresses.map((a) => ({ ...a })) };
  }

  update(customerId: string, patch: Partial<Omit<CustomerProfile, "customerId" | "updatedAt">>): CustomerProfile {
    const current = this.get(customerId);
    if (patch.email && patch.email.toLowerCase() !== current.email.toLowerCase()) {
      const owner = this.emailIndex.get(patch.email.toLowerCase());
      if (owner && owner !== customerId) {
        throw new PortalError("EMAIL_IN_USE", "Email address is already associated with another customer", 409);
      }
      this.emailIndex.delete(current.email.toLowerCase());
      this.emailIndex.set(patch.email.toLowerCase(), customerId);
    }
    if (patch.addresses) {
      const types = patch.addresses.map((a) => a.type);
      if (new Set(types).size !== types.length) {
        throw new PortalError("DUPLICATE_ADDRESS_TYPE", "Only one address per type is allowed", 422);
      }
      if (!types.includes("HOME") && current.addresses.some((a) => a.type === "HOME")) {
        throw new PortalError("HOME_ADDRESS_REQUIRED", "A home address cannot be removed, only replaced", 422);
      }
    }
    const updated: CustomerProfile = { ...current, ...patch, customerId, updatedAt: new Date().toISOString() };
    this.profiles.set(customerId, updated);
    return this.get(customerId);
  }

  /** Redacted view for support agents (no full contact data). */
  agentView(customerId: string): Record<string, string | boolean> {
    const p = this.get(customerId);
    return {
      customerId: p.customerId,
      name: `${p.firstName} ${p.lastName.charAt(0)}.`,
      email: `${p.email.charAt(0)}***@${p.email.split("@")[1]}`,
      phone: `***-***-${p.phone.slice(-4)}`,
      paperless: p.paperless,
      state: p.addresses.find((a) => a.type === "HOME")?.state ?? "",
    };
  }
}
