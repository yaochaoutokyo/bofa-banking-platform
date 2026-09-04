import { NextFunction, Request, Response, Router } from "express";
import { BillPayService } from "../services/billPayService";
import { CardControlService } from "../services/cardControlService";
import { DashboardService } from "../services/dashboardService";
import { ProfileService } from "../services/profileService";
import { PortalError } from "../types";
import { billPaymentSchema, cardControlsSchema, issues, payeeSchema, profileUpdateSchema } from "../validation/schemas";

export interface PortalDeps {
  profiles: ProfileService;
  billPay: BillPayService;
  cards: CardControlService;
  dashboard: DashboardService;
}

/** Customer identity comes from the session header set by the API gateway. */
function customerFrom(req: Request): string {
  const header = req.header("x-customer-id");
  if (!header) {
    throw new PortalError("UNAUTHENTICATED", "Missing customer session", 401);
  }
  return header;
}

export function createPortalRouter(deps: PortalDeps): Router {
  const router = Router();

  const wrap = (fn: (req: Request, res: Response) => void | Promise<void>) => (req: Request, res: Response, next: NextFunction) => {
    Promise.resolve(fn(req, res)).catch(next);
  };

  router.get("/dashboard", wrap((req, res) => {
    res.json(deps.dashboard.summary(customerFrom(req)));
  }));

  router.get("/profile", wrap((req, res) => {
    res.json(deps.profiles.get(customerFrom(req)));
  }));

  router.patch("/profile", wrap((req, res) => {
    const parsed = profileUpdateSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: issues(parsed.error) });
      return;
    }
    res.json(deps.profiles.update(customerFrom(req), parsed.data));
  }));

  router.get("/profile/agent-view", wrap((req, res) => {
    res.json(deps.profiles.agentView(customerFrom(req)));
  }));

  router.get("/payees", wrap((req, res) => {
    res.json(deps.billPay.listPayees(customerFrom(req)));
  }));

  router.post("/payees", wrap((req, res) => {
    const parsed = payeeSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: issues(parsed.error) });
      return;
    }
    const { name, accountNumber, category } = parsed.data;
    res.status(201).json(deps.billPay.addPayee(customerFrom(req), name, accountNumber, category));
  }));

  router.delete("/payees/:payeeId", wrap((req, res) => {
    deps.billPay.removePayee(customerFrom(req), req.params.payeeId);
    res.status(204).end();
  }));

  router.get("/payments", wrap((req, res) => {
    const status = req.query.status as "SCHEDULED" | "PAID" | undefined;
    res.json(deps.billPay.listPayments(customerFrom(req), status));
  }));

  router.post("/payments", wrap((req, res) => {
    const parsed = billPaymentSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: issues(parsed.error) });
      return;
    }
    res.status(201).json(deps.billPay.schedule(customerFrom(req), parsed.data));
  }));

  router.post("/payments/:paymentId/cancel", wrap((req, res) => {
    res.json(deps.billPay.cancel(customerFrom(req), req.params.paymentId));
  }));

  router.get("/cards", wrap((req, res) => {
    res.json(deps.cards.listForCustomer(customerFrom(req)));
  }));

  router.patch("/cards/:cardId", wrap((req, res) => {
    const parsed = cardControlsSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: issues(parsed.error) });
      return;
    }
    res.json(deps.cards.update(customerFrom(req), req.params.cardId, parsed.data));
  }));

  router.post("/cards/:cardId/report", wrap((req, res) => {
    const reason = req.body?.reason;
    if (reason !== "LOST" && reason !== "STOLEN") {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: [{ field: "reason", message: "LOST or STOLEN" }] });
      return;
    }
    res.json(deps.cards.reportLostOrStolen(customerFrom(req), req.params.cardId, reason));
  }));

  return router;
}

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof PortalError) {
    res.status(err.status).json({ code: err.code, message: err.message });
    return;
  }
  res.status(500).json({ code: "INTERNAL_ERROR", message: "Unexpected error" });
}
