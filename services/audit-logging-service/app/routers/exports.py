from datetime import datetime

from fastapi import APIRouter, HTTPException

from app.models.audit import EventCategory, QueryFilter
from app.services.alerts import detector
from app.services.export import exporter, siem
from app.services.trail import AuditError, trail

router = APIRouter(prefix="/api/v1", tags=["exports"])


@router.post("/exports")
def build_export(
    request_id: str,
    requested_by: str,
    purpose: str,
    category: EventCategory | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
) -> dict[str, object]:
    events = trail.query(QueryFilter(category=category, since=since, until=until, limit=1000))
    try:
        package = exporter.build(events, request_id, requested_by, purpose)
    except AuditError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc
    return {
        "manifest": package.manifest,
        "signature": package.signature,
        "first_sequence": package.first_sequence,
        "last_sequence": package.last_sequence,
        "body": package.body,
    }


@router.post("/siem/forward/{sequence}")
def forward(sequence: int) -> dict[str, bool]:
    try:
        event = trail.get(sequence)
        return {"forwarded": siem.forward(event)}
    except AuditError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.get("/alerts")
def alerts(limit: int = 50) -> list[dict[str, object]]:
    return detector.recent(limit)


@router.post("/alerts/evaluate/{sequence}")
def evaluate(sequence: int) -> list[dict[str, object]]:
    try:
        event = trail.get(sequence)
    except AuditError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc
    return [a.to_dict() for a in detector.evaluate(event)]
