from fastapi import FastAPI

from app.routers import events, exports, integrity, retention

app = FastAPI(
    title="Audit Logging Service",
    version="1.2.0",
    description="Append-only, hash-chained audit trail supporting SOX 404 and OCC examination evidence.",
)

app.include_router(events.router)
app.include_router(integrity.router)
app.include_router(retention.router)
app.include_router(exports.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "audit-logging-service"}
