import { NextFunction, Request, Response, Router } from "express";
import { CipService } from "../services/cipService";
import { screenName } from "../services/sanctions";
import { Applicant, KycError, LegalEntity } from "../types";

export function createKycRouter(cip: CipService): Router {
  const router = Router();

  router.post("/applicants/evaluate", (req: Request, res: Response, next: NextFunction) => {
    try {
      const body = req.body as Applicant;
      if (!body || typeof body.applicantId !== "string") {
        throw new KycError("APPLICANT_ID_REQUIRED", "applicantId is required", 422);
      }
      res.json(cip.evaluate(body));
    } catch (err) {
      next(err);
    }
  });

  router.post("/entities/evaluate", (req: Request, res: Response, next: NextFunction) => {
    try {
      const body = req.body as LegalEntity;
      if (!body || typeof body.entityId !== "string") {
        throw new KycError("ENTITY_ID_REQUIRED", "entityId is required", 422);
      }
      res.json(cip.evaluateEntity(body));
    } catch (err) {
      next(err);
    }
  });

  router.get("/results/:id", (req: Request, res: Response, next: NextFunction) => {
    try {
      res.json(cip.result(req.params.id));
    } catch (err) {
      next(err);
    }
  });

  router.get("/screen", (req: Request, res: Response) => {
    const name = String(req.query.name ?? "");
    if (name.length < 2) {
      res.status(422).json({ code: "NAME_REQUIRED", message: "name query parameter required" });
      return;
    }
    res.json({ name, hits: screenName(name, typeof req.query.dob === "string" ? req.query.dob : undefined) });
  });

  return router;
}

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof KycError) {
    res.status(err.status).json({ code: err.code, message: err.message });
    return;
  }
  res.status(500).json({ code: "INTERNAL_ERROR", message: "Unexpected error" });
}
