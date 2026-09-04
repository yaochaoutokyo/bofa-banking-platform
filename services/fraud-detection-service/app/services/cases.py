"""Fraud case management and SAR (Suspicious Activity Report) tracking.

A SAR must be filed within 30 days of detection (BSA 31 CFR 1020.320).
"""

import uuid
from datetime import datetime, timedelta, timezone

from app.models.risk import CaseStatus, FraudCase, ScoreResponse

SAR_DEADLINE = timedelta(days=30)


class CaseError(Exception):
    def __init__(self, code: str, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class CaseManager:
    def __init__(self) -> None:
        self._cases: dict[str, FraudCase] = {}

    def open_from_score(self, account_id: str, score: ScoreResponse) -> FraudCase | None:
        if score.decision.value == "APPROVE":
            return None
        for existing in self._cases.values():
            if existing.transaction_id == score.transaction_id:
                return existing
        case = FraudCase(
            case_id=f"CASE-{uuid.uuid4().hex[:8].upper()}",
            transaction_id=score.transaction_id,
            account_id=account_id,
            score=score.score,
            status=CaseStatus.OPEN,
            opened_at=datetime.now(timezone.utc),
        )
        self._cases[case.case_id] = case
        return case

    def get(self, case_id: str) -> FraudCase:
        case = self._cases.get(case_id)
        if case is None:
            raise CaseError("CASE_NOT_FOUND", f"Unknown case {case_id}", 404)
        return case

    def assign(self, case_id: str, analyst: str) -> FraudCase:
        case = self.get(case_id)
        if case.status in {CaseStatus.CONFIRMED_FRAUD, CaseStatus.FALSE_POSITIVE}:
            raise CaseError("CASE_CLOSED", "Closed cases cannot be reassigned", 409)
        if not analyst or not analyst.strip():
            raise CaseError("ANALYST_REQUIRED", "Analyst identity is required", 422)
        case.assigned_to = analyst.strip()
        case.status = CaseStatus.INVESTIGATING
        return case

    def add_note(self, case_id: str, analyst: str, note: str) -> FraudCase:
        case = self.get(case_id)
        if not note or len(note.strip()) < 5:
            raise CaseError("NOTE_TOO_SHORT", "Notes must be at least 5 characters", 422)
        if case.assigned_to and case.assigned_to != analyst:
            raise CaseError("NOT_ASSIGNEE", "Only the assigned analyst may add notes", 403)
        stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
        case.notes.append(f"[{stamp}] {analyst}: {note.strip()}")
        return case

    def close(self, case_id: str, analyst: str, confirmed_fraud: bool, sar_filed: bool = False) -> FraudCase:
        case = self.get(case_id)
        if case.status is not CaseStatus.INVESTIGATING:
            raise CaseError("CASE_NOT_INVESTIGATING", "Case must be under investigation to close", 409)
        if case.assigned_to != analyst:
            raise CaseError("NOT_ASSIGNEE", "Only the assigned analyst may close the case", 403)
        if confirmed_fraud and not sar_filed and case.score >= 75:
            raise CaseError("SAR_REQUIRED", "Confirmed fraud with score >= 75 requires a SAR", 422)
        case.status = CaseStatus.CONFIRMED_FRAUD if confirmed_fraud else CaseStatus.FALSE_POSITIVE
        case.sar_filed = sar_filed
        return case

    def sar_overdue(self, as_of: datetime | None = None) -> list[FraudCase]:
        now = as_of or datetime.now(timezone.utc)
        return [
            c
            for c in self._cases.values()
            if c.status in {CaseStatus.OPEN, CaseStatus.INVESTIGATING}
            and c.score >= 75
            and now - c.opened_at > SAR_DEADLINE
        ]

    def list_open(self) -> list[FraudCase]:
        return [c for c in self._cases.values() if c.status in {CaseStatus.OPEN, CaseStatus.INVESTIGATING}]


cases = CaseManager()
