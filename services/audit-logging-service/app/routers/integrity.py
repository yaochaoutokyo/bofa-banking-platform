from fastapi import APIRouter

from app.models.audit import IntegrityReport
from app.services.trail import trail

router = APIRouter(prefix="/api/v1/integrity", tags=["integrity"])


@router.get("/verify", response_model=IntegrityReport)
def verify() -> IntegrityReport:
    return trail.verify_chain()


@router.get("/head")
def head() -> dict[str, str | int]:
    return {"head_hash": trail.head_hash(), "count": trail.count()}


@router.get("/dead-letters")
def dead_letters() -> list[dict[str, str]]:
    return trail.dead_letters()


@router.post("/dead-letters/replay")
def replay() -> dict[str, int]:
    return {"replayed": trail.replay_dead_letters()}
