import { z } from "zod";

const US_STATES = new Set([
  "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN","IA","KS","KY","LA","ME","MD",
  "MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC",
  "SD","TN","TX","UT","VT","VA","WA","WV","WI","WY","DC","PR",
]);

export const addressSchema = z.object({
  type: z.enum(["HOME", "MAILING"]),
  line1: z.string().trim().min(3).max(80),
  line2: z.string().trim().max(80).optional(),
  city: z.string().trim().min(2).max(60),
  state: z.string().trim().toUpperCase().refine((s) => US_STATES.has(s), "Unknown state code"),
  postalCode: z.string().regex(/^\d{5}(-\d{4})?$/, "ZIP must be 12345 or 12345-6789"),
});

export const profileUpdateSchema = z
  .object({
    firstName: z.string().trim().min(1).max(50).optional(),
    lastName: z.string().trim().min(1).max(50).optional(),
    email: z.string().email().max(120).optional(),
    phone: z.string().regex(/^\+1\d{10}$/, "Phone must be E.164 US (+1XXXXXXXXXX)").optional(),
    preferredLanguage: z.enum(["en", "es"]).optional(),
    paperless: z.boolean().optional(),
    addresses: z.array(addressSchema).max(2).optional(),
  })
  .refine((v) => Object.keys(v).length > 0, "At least one field required");

export const payeeSchema = z.object({
  name: z.string().trim().min(2).max(60),
  accountNumber: z.string().regex(/^\d{4,17}$/, "Account number must be 4-17 digits"),
  category: z.enum(["UTILITY", "CREDIT_CARD", "MORTGAGE", "PERSON", "OTHER"]),
});

export const billPaymentSchema = z.object({
  payeeId: z.string().min(1),
  fromAccountId: z.string().regex(/^ACC-\d{4,}$/),
  amountMinor: z.number().int().positive().max(25_000_00),
  scheduledFor: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  memo: z.string().trim().max(60).optional(),
});

export const cardControlsSchema = z
  .object({
    locked: z.boolean().optional(),
    internationalEnabled: z.boolean().optional(),
    onlineEnabled: z.boolean().optional(),
    atmDailyLimitMinor: z.number().int().min(100_00).max(1_500_00).optional(),
    merchantBlocks: z.array(z.enum(["GAMBLING", "ADULT", "ALCOHOL", "CRYPTO", "TRAVEL"])).max(5).optional(),
  })
  .refine((v) => Object.keys(v).length > 0, "At least one control required");

export function issues(error: z.ZodError): { field: string; message: string }[] {
  return error.issues.map((i) => ({ field: i.path.join(".") || "(root)", message: i.message }));
}
