import express, { Express } from "express";
import { InMemoryLedgerFeed } from "./services/ledgerFeed";
import { createStatementRouter, errorHandler } from "./routes/statementRoutes";

export function createApp(feed = new InMemoryLedgerFeed()): Express {
  const app = express();
  app.use(express.json());
  app.get("/health", (_req, res) => res.json({ status: "ok", service: "statement-service" }));
  app.use("/api/v1/statements", createStatementRouter(feed));
  app.use(errorHandler);
  return app;
}
