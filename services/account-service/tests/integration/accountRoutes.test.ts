import request from "supertest";
import { createApp } from "../../src/app";
import { buildAccount, createHarness } from "../helpers/testHarness";

describe("account routes", () => {
  it("GET /health", async () => {
    const { service } = createHarness();
    const res = await request(createApp(service)).get("/health");
    expect(res.status).toBe(200);
    expect(res.body.service).toBe("account-service");
  });

  it("POST /api/v1/accounts creates an account", async () => {
    const { service } = createHarness();
    const res = await request(createApp(service))
      .post("/api/v1/accounts")
      .send({ customerId: "CUST-1001", type: "SAVINGS", initialDepositMinor: 100_00 });
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ id: "ACC-0001", status: "ACTIVE" });
  });

  it("POST /api/v1/accounts returns structured validation errors", async () => {
    const { service } = createHarness();
    const res = await request(createApp(service)).post("/api/v1/accounts").send({ customerId: "bad", type: "CHECKING" });
    expect(res.status).toBe(400);
    expect(res.body.code).toBe("VALIDATION_FAILED");
    expect(res.body.errors[0].field).toBe("customerId");
  });

  it("maps AccountError to its HTTP status", async () => {
    const { service } = createHarness();
    const res = await request(createApp(service)).get("/api/v1/accounts/missing");
    expect(res.status).toBe(404);
    expect(res.body.code).toBe("ACCOUNT_NOT_FOUND");
  });

  it("GET /api/v1/accounts?customerId= lists accounts", async () => {
    const { service } = createHarness([buildAccount({ id: "A" }), buildAccount({ id: "B", status: "CLOSED" })]);
    const app = createApp(service);
    expect((await request(app).get("/api/v1/accounts?customerId=CUST-1001")).body).toHaveLength(1);
    expect((await request(app).get("/api/v1/accounts?customerId=CUST-1001&includeClosed=true")).body).toHaveLength(2);
    expect((await request(app).get("/api/v1/accounts")).status).toBe(400);
  });

  it("PATCH and status transitions round-trip", async () => {
    const { service } = createHarness([buildAccount()]);
    const app = createApp(service);

    expect((await request(app).patch("/api/v1/accounts/ACC-0001").send({})).status).toBe(400);
    expect((await request(app).patch("/api/v1/accounts/ACC-0001").send({ nickname: "Bills" })).body.nickname).toBe("Bills");

    const frozen = await request(app).post("/api/v1/accounts/ACC-0001/status").send({ action: "FREEZE", reason: "fraud" });
    expect(frozen.body.status).toBe("FROZEN");

    const bad = await request(app).post("/api/v1/accounts/ACC-0001/status").send({ action: "ACTIVATE", reason: "oops" });
    expect(bad.status).toBe(409);
    expect((await request(app).post("/api/v1/accounts/ACC-0001/status").send({ action: "NOPE" })).status).toBe(400);
  });

  it("returns 500 for unexpected errors", async () => {
    const { service } = createHarness();
    jest.spyOn(service, "get").mockRejectedValueOnce(new Error("boom"));
    const res = await request(createApp(service)).get("/api/v1/accounts/ACC-0001");
    expect(res.status).toBe(500);
    expect(res.body.code).toBe("INTERNAL_ERROR");
  });
});
