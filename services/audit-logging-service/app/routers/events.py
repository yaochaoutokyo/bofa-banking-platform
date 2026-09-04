from datetime import datetime

from fastapi import APIRouter, HTTPException

from app.models.audit import AuditEvent, AuditEventIn, EventCategory, QueryFilter
from app.services.trail import AuditError, trail

router = APIRouter(prefix="/api/v1/events", tags=["events"])


@router.post("", response_model=AuditEvent, status_code=201)
def append(event: AuditEventIn) -> AuditEvent:
    try:
        return trail.append(event)
    except AuditError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.post("/batch", response_model=list[AuditEvent], status_code=201)
def append_batch(events: list[AuditEventIn]) -> list[AuditEvent]:
    if not events:
        raise HTTPException(status_code=422, detail={"code": "BATCH_EMPTY", "message": "Batch must not be empty"})
    if len(events) > 500:
        raise HTTPException(status_code=413, detail={"code": "BATCH_TOO_LARGE", "message": "Max 500 per batch"})
    written: list[AuditEvent] = []
    for event in events:
        try:
            written.append(trail.append(event))
        except AuditError as exc:
            raise HTTPException(
                status_code=exc.status,
                detail={"code": exc.code, "message": exc.message, "written_before_failure": len(written)},
            ) from exc
    return written


@router.get("/{sequence}", response_model=AuditEvent)
def get(sequence: int) -> AuditEvent:
    try:
        return trail.get(sequence)
    except AuditError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.get("", response_model=list[AuditEvent])
def query(
    category: EventCategory | None = None,
    actor: str | None = None,
    subject: str | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    limit: int = 100,
) -> list[AuditEvent]:
    flt = QueryFilter(category=category, actor=actor, subject=subject, since=since, until=until, limit=limit)
    return trail.query(flt)


@router.put("/{sequence}", status_code=405)
@router.delete("/{sequence}", status_code=405)
def immutable(sequence: int) -> None:
    raise HTTPException(
        status_code=405,
        detail={"code": "IMMUTABLE", "message": "Audit events cannot be modified or deleted"},
    )
