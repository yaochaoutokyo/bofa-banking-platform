import express, { Express } from "express";
import { createKycRouter, errorHandler } from "./routes/kycRoutes";
import { CipService } from "./services/cipService";

export function createApp(cip = new CipService()): Express {
  const app = express();
  app.use(express.json());
  app.get("/health", (_req, res) => res.json({ status: "ok", service: "kyc-service" }));
  app.use("/api/v1/kyc", createKycRouter(cip));
  app.use(errorHandler);
  return app;
}
