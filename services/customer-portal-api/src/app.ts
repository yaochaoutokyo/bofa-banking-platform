import express, { Express } from "express";
import { createPortalRouter, errorHandler, PortalDeps } from "./routes/portalRoutes";
import { BillPayService } from "./services/billPayService";
import { CardControlService } from "./services/cardControlService";
import { DashboardService } from "./services/dashboardService";
import { ProfileService } from "./services/profileService";

export function defaultDeps(): PortalDeps {
  const profiles = new ProfileService();
  const billPay = new BillPayService();
  const cards = new CardControlService();
  return { profiles, billPay, cards, dashboard: new DashboardService(profiles, billPay, cards) };
}

export function createApp(deps: PortalDeps = defaultDeps()): Express {
  const app = express();
  app.use(express.json({ limit: "32kb" }));
  app.get("/health", (_req, res) => res.json({ status: "ok", service: "customer-portal-api" }));
  app.use("/api/v1/portal", createPortalRouter(deps));
  app.use(errorHandler);
  return app;
}
