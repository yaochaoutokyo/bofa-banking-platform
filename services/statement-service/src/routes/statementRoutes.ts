import { NextFunction, Request, Response, Router } from "express";
import { randomUUID } from "node:crypto";
import { InMemoryLedgerFeed } from "../services/ledgerFeed";
import { accrueInterest, apyTierFor } from "../services/interest";
import { renderCsv, renderText } from "../services/render";
import { buildStatement, parsePeriod } from "../services/statementBuilder";
import { StatementError } from "../types";

export function createStatementRouter(feed: InMemoryLedgerFeed): Router {
  const router = Router();

  router.get("/:accountId", async (req: Request, res: Response, next: NextFunction) => {
    try {
      const period = parsePeriod({ start: req.query.start, end: req.query.end });
      const lines = await feed.linesFor(req.params.accountId);
      if (!lines) {
        throw new StatementError("ACCOUNT_NOT_FOUND", "Unknown account", 404);
      }
      const statement = buildStatement(req.params.accountId, lines, period, new Date(), () => `STMT-${randomUUID()}`);
      const format = String(req.query.format ?? "json");
      if (format === "text") {
        res.type("text/plain").send(renderText(statement));
      } else if (format === "csv") {
        res.type("text/csv").send(renderCsv(statement));
      } else {
        res.json(statement);
      }
    } catch (err) {
      next(err);
    }
  });

  router.get("/:accountId/interest-preview", async (req: Request, res: Response, next: NextFunction) => {
    try {
      const period = parsePeriod({ start: req.query.start, end: req.query.end });
      const lines = await feed.linesFor(req.params.accountId);
      if (!lines) {
        throw new StatementError("ACCOUNT_NOT_FOUND", "Unknown account", 404);
      }
      const product = String(req.query.product ?? "SAVINGS");
      const opening = lines
        .filter((l) => l.postedAt < `${period.start}T00:00:00.000Z`)
        .reduce((s, l) => s + l.amountMinor, 0);
      const apy = apyTierFor(opening, product);
      const interest = accrueInterest(opening, lines, period.start, period.end, apy);
      res.json({ accountId: req.params.accountId, period, apyBasisPoints: apy, projectedInterestMinor: interest });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof StatementError) {
    res.status(err.status).json({ code: err.code, message: err.message });
    return;
  }
  res.status(500).json({ code: "INTERNAL_ERROR", message: "Unexpected error" });
}
