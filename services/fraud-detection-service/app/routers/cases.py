from fastapi import APIRouter, HTTPException

from app.models.risk import FraudCase
from app.services.cases import CaseError, cases

router = APIRouter(prefix="/api/v1/cases", tags=["cases"])


def _http(exc: CaseError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message})


@router.get("", response_model=list[FraudCase])
def list_open() -> list[FraudCase]:
    return cases.list_open()


@router.get("/sar-overdue", response_model=list[FraudCase])
def sar_overdue() -> list[FraudCase]:
    return cases.sar_overdue()


@router.get("/{case_id}", response_model=FraudCase)
def get(case_id: str) -> FraudCase:
    try:
        return cases.get(case_id)
    except CaseError as exc:
        raise _http(exc) from exc


@router.post("/{case_id}/assign", response_model=FraudCase)
def assign(case_id: str, analyst: str) -> FraudCase:
    try:
        return cases.assign(case_id, analyst)
    except CaseError as exc:
        raise _http(exc) from exc


@router.post("/{case_id}/notes", response_model=FraudCase)
def add_note(case_id: str, analyst: str, note: str) -> FraudCase:
    try:
        return cases.add_note(case_id, analyst, note)
    except CaseError as exc:
        raise _http(exc) from exc


@router.post("/{case_id}/close", response_model=FraudCase)
def close(case_id: str, analyst: str, confirmed_fraud: bool, sar_filed: bool = False) -> FraudCase:
    try:
        return cases.close(case_id, analyst, confirmed_fraud, sar_filed)
    except CaseError as exc:
        raise _http(exc) from exc
