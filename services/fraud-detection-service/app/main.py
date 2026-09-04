from fastapi import FastAPI

from app.routers import cases, scoring

app = FastAPI(title="Fraud Detection Service", version="2.1.0")
app.include_router(scoring.router)
app.include_router(cases.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "fraud-detection-service"}
