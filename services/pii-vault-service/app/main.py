from fastapi import FastAPI

from app.routers import tokens, masking, access

app = FastAPI(
    title="PII Vault Service",
    version="0.4.2",
    description="Format-preserving tokenization, masking and access-controlled detokenization for customer PII.",
)

app.include_router(tokens.router)
app.include_router(masking.router)
app.include_router(access.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "pii-vault-service"}
