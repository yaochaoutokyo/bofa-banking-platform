import { z } from "zod";

export const ACCOUNT_TYPES = ["CHECKING", "SAVINGS", "MONEY_MARKET", "CD"] as const;
export const SUPPORTED_CURRENCIES = ["USD", "EUR", "GBP"] as const;

export const customerIdSchema = z
  .string()
  .regex(/^CUST-\d{4,10}$/, "customerId must look like CUST-1234");

export const openAccountSchema = z.object({
  customerId: customerIdSchema,
  type: z.enum(ACCOUNT_TYPES),
  currency: z.enum(SUPPORTED_CURRENCIES).default("USD"),
  nickname: z.string().trim().min(1).max(40).optional(),
  initialDepositMinor: z.number().int().nonnegative().max(1_000_000_000_00).optional(),
});

export const updateAccountSchema = z
  .object({
    nickname: z.string().trim().min(1).max(40).optional(),
  })
  .refine((v) => Object.keys(v).length > 0, { message: "At least one field must be provided" });

export const statusTransitionSchema = z.object({
  action: z.enum(["ACTIVATE", "FREEZE", "UNFREEZE", "CLOSE"]),
  reason: z.string().trim().min(3).max(200),
});

export type OpenAccountBody = z.infer<typeof openAccountSchema>;
export type UpdateAccountBody = z.infer<typeof updateAccountSchema>;
export type StatusTransitionBody = z.infer<typeof statusTransitionSchema>;

export interface ValidationFailure {
  field: string;
  message: string;
}

export function formatZodIssues(error: z.ZodError): ValidationFailure[] {
  return error.issues.map((issue) => ({
    field: issue.path.join(".") || "(root)",
    message: issue.message,
  }));
}
