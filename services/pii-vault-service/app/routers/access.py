from fastapi import APIRouter, HTTPException

from app.models.pii import AccessPolicy, DisclosureRecord
from app.services.vault import VaultError, vault

router = APIRouter(prefix="/api/v1/access", tags=["access-control"])


@router.get("/policies/{service}", response_model=AccessPolicy)
def get_policy(service: str) -> AccessPolicy:
    try:
        return vault.policy_for(service)
    except VaultError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.put("/policies", response_model=AccessPolicy)
def upsert_policy(policy: AccessPolicy) -> AccessPolicy:
    try:
        return vault.upsert_policy(policy)
    except VaultError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.get("/disclosures", response_model=list[DisclosureRecord])
def disclosures(token: str | None = None, service: str | None = None) -> list[DisclosureRecord]:
    return vault.disclosures(token=token, service=service)
