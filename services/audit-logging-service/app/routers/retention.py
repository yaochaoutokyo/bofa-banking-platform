from fastapi import APIRouter, HTTPException

from app.models.audit import AuditEvent, EventCategory, RetentionPolicy
from app.services.retention import retention
from app.services.trail import AuditError, trail

router = APIRouter(prefix="/api/v1/retention", tags=["retention"])


def _http(exc: AuditError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message})


@router.get("/policies/{category}", response_model=RetentionPolicy)
def get_policy(category: EventCategory) -> RetentionPolicy:
    return retention.policy(category)


@router.put("/policies", response_model=RetentionPolicy)
def update_policy(policy: RetentionPolicy, approved_by: str | None = None) -> RetentionPolicy:
    try:
        return retention.update_policy(policy, approved_by)
    except AuditError as exc:
        raise _http(exc) from exc


@router.post("/holds", status_code=201)
def place_hold(subject: str, reference: str) -> dict[str, str]:
    try:
        retention.place_hold(subject, reference)
    except AuditError as exc:
        raise _http(exc) from exc
    return {"subject": subject, "reference": reference}


@router.delete("/holds", status_code=204)
def release_hold(subject: str, reference: str) -> None:
    try:
        retention.release_hold(subject, reference)
    except AuditError as exc:
        raise _http(exc) from exc


@router.get("/archivable", response_model=list[AuditEvent])
def archivable() -> list[AuditEvent]:
    return retention.archivable(trail.sink.records)
