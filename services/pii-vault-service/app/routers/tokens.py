from fastapi import APIRouter, HTTPException

from app.models.pii import DetokenizeRequest, DetokenizeResponse, TokenizeRequest, TokenizeResponse
from app.services import masking
from app.services.vault import VaultError, vault

router = APIRouter(prefix="/api/v1/tokens", tags=["tokens"])


def _raise(exc: VaultError) -> None:
    raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message})


@router.post("", response_model=TokenizeResponse, status_code=201)
def tokenize(request: TokenizeRequest) -> TokenizeResponse:
    try:
        entry = vault.tokenize(request.pii_type, request.value, request.owner_customer_id, request.purpose)
    except VaultError as exc:
        _raise(exc)
    return TokenizeResponse(
        token=entry.token,
        pii_type=entry.pii_type,
        masked=masking.mask(entry.pii_type, entry.value),
        created_at=entry.created_at,
    )


@router.post("/detokenize", response_model=DetokenizeResponse)
def detokenize(request: DetokenizeRequest) -> DetokenizeResponse:
    try:
        entry, record = vault.detokenize(
            request.token, request.requesting_service, request.requesting_principal, request.justification
        )
    except VaultError as exc:
        _raise(exc)
    return DetokenizeResponse(
        token=entry.token,
        pii_type=entry.pii_type,
        value=entry.value,
        disclosed_to=record.disclosed_to_service,
        disclosed_at=record.disclosed_at,
    )


@router.get("/{token}/masked")
def masked(token: str) -> dict[str, str]:
    try:
        return {"token": token, "masked": vault.masked(token)}
    except VaultError as exc:
        _raise(exc)


@router.delete("/{token}", status_code=204)
def revoke(token: str, owner_customer_id: str) -> None:
    try:
        vault.revoke(token, owner_customer_id)
    except VaultError as exc:
        _raise(exc)


@router.delete("/customers/{customer_id}")
def purge_customer(customer_id: str) -> dict[str, int]:
    return {"purged": vault.purge_customer(customer_id)}
