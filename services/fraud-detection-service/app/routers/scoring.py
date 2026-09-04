from fastapi import APIRouter, HTTPException

from app.models.risk import ScoreRequest, ScoreResponse
from app.services.cases import cases
from app.services.scoring import engine

router = APIRouter(prefix="/api/v1/risk", tags=["scoring"])


@router.post("/score", response_model=ScoreResponse)
def score(request: ScoreRequest) -> ScoreResponse:
    try:
        result = engine.score(request)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail={"code": "DUPLICATE_TRANSACTION", "message": str(exc)}) from exc
    cases.open_from_score(request.account_id, result)
    return result


@router.get("/profile/{account_id}")
def profile(account_id: str) -> dict[str, object]:
    p = engine.profile(account_id)
    return {
        "account_id": account_id,
        "transaction_count": p.transaction_count,
        "average_amount": str(p.average_amount),
        "known_devices": sorted(p.known_devices),
        "home_country": p.home_country,
    }
