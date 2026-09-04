import { Router, Request, Response, NextFunction } from "express";
import { AccountError } from "../domain";
import { AccountService } from "../services/accountService";
import {
  customerIdSchema,
  formatZodIssues,
  openAccountSchema,
  statusTransitionSchema,
  updateAccountSchema,
} from "../validation/schemas";

function pathParam(req: Request, name: string): string {
  return req.params[name] ?? "";
}

export function createAccountRouter(service: AccountService): Router {
  const router = Router();

  router.post("/", async (req: Request, res: Response, next: NextFunction) => {
    const parsed = openAccountSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: formatZodIssues(parsed.error) });
      return;
    }
    try {
      const account = await service.open(parsed.data);
      res.status(201).json(account);
    } catch (err) {
      next(err);
    }
  });

  router.get("/:id", async (req: Request, res: Response, next: NextFunction) => {
    try {
      res.json(await service.get(pathParam(req, "id")));
    } catch (err) {
      next(err);
    }
  });

  router.get("/", async (req: Request, res: Response, next: NextFunction) => {
    const customerId = customerIdSchema.safeParse(req.query.customerId);
    if (!customerId.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: formatZodIssues(customerId.error) });
      return;
    }
    try {
      const includeClosed = req.query.includeClosed === "true";
      res.json(await service.listForCustomer(customerId.data, includeClosed));
    } catch (err) {
      next(err);
    }
  });

  router.patch("/:id", async (req: Request, res: Response, next: NextFunction) => {
    const parsed = updateAccountSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: formatZodIssues(parsed.error) });
      return;
    }
    try {
      res.json(await service.update(pathParam(req, "id"), parsed.data));
    } catch (err) {
      next(err);
    }
  });

  router.post("/:id/status", async (req: Request, res: Response, next: NextFunction) => {
    const parsed = statusTransitionSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ code: "VALIDATION_FAILED", errors: formatZodIssues(parsed.error) });
      return;
    }
    try {
      res.json(await service.transition(pathParam(req, "id"), parsed.data.action, parsed.data.reason));
    } catch (err) {
      next(err);
    }
  });

  return router;
}

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof AccountError) {
    res.status(err.status).json({ code: err.code, message: err.message });
    return;
  }
  res.status(500).json({ code: "INTERNAL_ERROR", message: "Unexpected error" });
}
