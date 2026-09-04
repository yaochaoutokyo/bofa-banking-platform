import express, { Express } from "express";
import { AccountService } from "./services/accountService";
import { createAccountRouter, errorHandler } from "./routes/accountRoutes";

/**
 * Composition root. Accepts a fully-constructed service so tests can wire in
 * fakes without touching module-level singletons.
 */
export function createApp(service: AccountService): Express {
  const app = express();
  app.use(express.json({ limit: "64kb" }));
  app.get("/health", (_req, res) => res.json({ status: "ok", service: "account-service" }));
  app.use("/api/v1/accounts", createAccountRouter(service));
  app.use(errorHandler);
  return app;
}
