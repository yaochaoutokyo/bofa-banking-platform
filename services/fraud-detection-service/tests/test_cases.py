from datetime import datetime, timedelta, timezone

import pytest

from app.models.risk import CaseStatus, Decision, ScoreResponse
from app.services.cases import SAR_DEADLINE, CaseError, CaseManager

ANALYST = "analyst.a"
OTHER = "analyst.b"
NOW = datetime(2024, 6, 1, 12, 0, tzinfo=timezone.utc)


def _score(
    transaction_id: str = "TXN-1", score: int = 50, decision: Decision = Decision.REVIEW
) -> ScoreResponse:
    return ScoreResponse(
        transaction_id=transaction_id,
        score=score,
        decision=decision,
        signals=[],
        model_version="rules-test",
    )


@pytest.fixture
def manager() -> CaseManager:
    return CaseManager()


def _open(manager: CaseManager, transaction_id: str = "TXN-1", score: int = 50):
    decision = Decision.DECLINE if score >= 75 else Decision.REVIEW
    case = manager.open_from_score("ACC-1", _score(transaction_id, score, decision))
    assert case is not None
    return case


def _investigating(manager: CaseManager, score: int = 50, analyst: str = ANALYST):
    case = _open(manager, score=score)
    return manager.assign(case.case_id, analyst)


# ---------------------------------------------------------------- open_from_score


def test_open_from_score_returns_none_for_approve(manager):
    assert manager.open_from_score("ACC-1", _score(score=5, decision=Decision.APPROVE)) is None
    assert manager.list_open() == []


@pytest.mark.parametrize("decision,score", [(Decision.REVIEW, 45), (Decision.DECLINE, 90)])
def test_open_from_score_opens_case_for_review_and_decline(manager, decision, score):
    case = manager.open_from_score("ACC-9", _score("TXN-X", score, decision))
    assert case is not None
    assert case.case_id.startswith("CASE-")
    assert case.transaction_id == "TXN-X"
    assert case.account_id == "ACC-9"
    assert case.score == score
    assert case.status is CaseStatus.OPEN
    assert case.assigned_to is None
    assert case.sar_filed is False
    assert case.notes == []
    assert manager.get(case.case_id) is case


def test_open_from_score_dedupes_same_transaction(manager):
    first = _open(manager, "TXN-DUP")
    second = manager.open_from_score("ACC-1", _score("TXN-DUP", 60, Decision.REVIEW))
    assert second is first
    assert len(manager.list_open()) == 1


# ---------------------------------------------------------------- get


def test_get_unknown_case_raises_not_found(manager):
    with pytest.raises(CaseError) as excinfo:
        manager.get("CASE-NOPE")
    assert excinfo.value.code == "CASE_NOT_FOUND"
    assert excinfo.value.status == 404
    assert "CASE-NOPE" in excinfo.value.message


# ---------------------------------------------------------------- assign


def test_assign_sets_analyst_and_investigating_status(manager):
    case = _open(manager)
    result = manager.assign(case.case_id, "  analyst.a  ")
    assert result is case
    assert case.assigned_to == ANALYST
    assert case.status is CaseStatus.INVESTIGATING


def test_assign_allows_reassignment_while_investigating(manager):
    case = _investigating(manager)
    manager.assign(case.case_id, OTHER)
    assert case.assigned_to == OTHER
    assert case.status is CaseStatus.INVESTIGATING


@pytest.mark.parametrize("confirmed_fraud", [True, False])
def test_assign_closed_case_raises_case_closed(manager, confirmed_fraud):
    case = _investigating(manager, score=50)
    manager.close(case.case_id, ANALYST, confirmed_fraud=confirmed_fraud)
    with pytest.raises(CaseError) as excinfo:
        manager.assign(case.case_id, OTHER)
    assert excinfo.value.code == "CASE_CLOSED"
    assert excinfo.value.status == 409


@pytest.mark.parametrize("analyst", ["", "   ", "\t\n"])
def test_assign_blank_analyst_raises_analyst_required(manager, analyst):
    case = _open(manager)
    with pytest.raises(CaseError) as excinfo:
        manager.assign(case.case_id, analyst)
    assert excinfo.value.code == "ANALYST_REQUIRED"
    assert excinfo.value.status == 422
    assert case.status is CaseStatus.OPEN
    assert case.assigned_to is None


def test_assign_unknown_case_raises_not_found(manager):
    with pytest.raises(CaseError) as excinfo:
        manager.assign("CASE-NOPE", ANALYST)
    assert excinfo.value.code == "CASE_NOT_FOUND"


# ---------------------------------------------------------------- add_note


def test_add_note_appends_timestamped_note(manager):
    case = _investigating(manager)
    manager.add_note(case.case_id, ANALYST, "  Reviewed merchant history  ")
    assert len(case.notes) == 1
    note = case.notes[0]
    assert note.startswith("[")
    stamp = note[1 : note.index("]")]
    assert datetime.fromisoformat(stamp).tzinfo is not None
    assert note.endswith(f"] {ANALYST}: Reviewed merchant history")


def test_add_note_on_unassigned_case_allows_any_analyst(manager):
    case = _open(manager)
    manager.add_note(case.case_id, OTHER, "Triage pending assignment")
    assert len(case.notes) == 1
    assert OTHER in case.notes[0]


def test_add_note_accumulates_multiple_notes(manager):
    case = _investigating(manager)
    manager.add_note(case.case_id, ANALYST, "first note")
    manager.add_note(case.case_id, ANALYST, "second note")
    assert len(case.notes) == 2


@pytest.mark.parametrize("note", ["", "    ", "abcd", " ab  "])
def test_add_note_too_short_raises(manager, note):
    case = _investigating(manager)
    with pytest.raises(CaseError) as excinfo:
        manager.add_note(case.case_id, ANALYST, note)
    assert excinfo.value.code == "NOTE_TOO_SHORT"
    assert excinfo.value.status == 422
    assert case.notes == []


def test_add_note_exactly_five_chars_is_accepted(manager):
    case = _investigating(manager)
    manager.add_note(case.case_id, ANALYST, "abcde")
    assert len(case.notes) == 1


def test_add_note_by_non_assignee_raises(manager):
    case = _investigating(manager)
    with pytest.raises(CaseError) as excinfo:
        manager.add_note(case.case_id, OTHER, "Trying to interject")
    assert excinfo.value.code == "NOT_ASSIGNEE"
    assert excinfo.value.status == 403
    assert case.notes == []


# ---------------------------------------------------------------- close


def test_close_as_confirmed_fraud_below_sar_threshold(manager):
    case = _investigating(manager, score=74)
    result = manager.close(case.case_id, ANALYST, confirmed_fraud=True)
    assert result is case
    assert case.status is CaseStatus.CONFIRMED_FRAUD
    assert case.sar_filed is False
    assert case not in manager.list_open()


def test_close_as_false_positive(manager):
    case = _investigating(manager, score=90)
    manager.close(case.case_id, ANALYST, confirmed_fraud=False)
    assert case.status is CaseStatus.FALSE_POSITIVE
    assert case.sar_filed is False


def test_close_open_case_raises_not_investigating(manager):
    case = _open(manager)
    with pytest.raises(CaseError) as excinfo:
        manager.close(case.case_id, ANALYST, confirmed_fraud=False)
    assert excinfo.value.code == "CASE_NOT_INVESTIGATING"
    assert excinfo.value.status == 409
    assert case.status is CaseStatus.OPEN


def test_close_already_closed_case_raises_not_investigating(manager):
    case = _investigating(manager)
    manager.close(case.case_id, ANALYST, confirmed_fraud=False)
    with pytest.raises(CaseError) as excinfo:
        manager.close(case.case_id, ANALYST, confirmed_fraud=True)
    assert excinfo.value.code == "CASE_NOT_INVESTIGATING"


def test_close_by_wrong_analyst_raises(manager):
    case = _investigating(manager)
    with pytest.raises(CaseError) as excinfo:
        manager.close(case.case_id, OTHER, confirmed_fraud=False)
    assert excinfo.value.code == "NOT_ASSIGNEE"
    assert excinfo.value.status == 403
    assert case.status is CaseStatus.INVESTIGATING


@pytest.mark.parametrize("score", [75, 90, 100])
def test_close_confirmed_fraud_high_score_without_sar_raises(manager, score):
    case = _investigating(manager, score=score)
    with pytest.raises(CaseError) as excinfo:
        manager.close(case.case_id, ANALYST, confirmed_fraud=True)
    assert excinfo.value.code == "SAR_REQUIRED"
    assert excinfo.value.status == 422
    assert case.status is CaseStatus.INVESTIGATING
    assert case.sar_filed is False


def test_close_confirmed_fraud_high_score_with_sar_succeeds(manager):
    case = _investigating(manager, score=80)
    manager.close(case.case_id, ANALYST, confirmed_fraud=True, sar_filed=True)
    assert case.status is CaseStatus.CONFIRMED_FRAUD
    assert case.sar_filed is True


def test_close_false_positive_high_score_does_not_require_sar(manager):
    case = _investigating(manager, score=95)
    manager.close(case.case_id, ANALYST, confirmed_fraud=False)
    assert case.status is CaseStatus.FALSE_POSITIVE


# ---------------------------------------------------------------- sar_overdue


def test_sar_overdue_flags_old_high_score_open_and_investigating_cases(manager):
    open_case = _open(manager, "TXN-A", score=80)
    investigating = _open(manager, "TXN-B", score=75)
    manager.assign(investigating.case_id, ANALYST)
    for case in (open_case, investigating):
        case.opened_at = NOW - SAR_DEADLINE - timedelta(seconds=1)

    overdue = manager.sar_overdue(as_of=NOW)
    assert {c.case_id for c in overdue} == {open_case.case_id, investigating.case_id}


def test_sar_overdue_excludes_cases_exactly_at_deadline(manager):
    case = _open(manager, score=90)
    case.opened_at = NOW - SAR_DEADLINE
    assert manager.sar_overdue(as_of=NOW) == []


def test_sar_overdue_excludes_recent_cases(manager):
    case = _open(manager, score=90)
    case.opened_at = NOW - timedelta(days=29)
    assert manager.sar_overdue(as_of=NOW) == []


def test_sar_overdue_excludes_low_score_cases(manager):
    case = _open(manager, score=74)
    case.opened_at = NOW - timedelta(days=60)
    assert manager.sar_overdue(as_of=NOW) == []


def test_sar_overdue_excludes_closed_cases(manager):
    case = _investigating(manager, score=90)
    case.opened_at = NOW - timedelta(days=60)
    manager.close(case.case_id, ANALYST, confirmed_fraud=True, sar_filed=True)
    assert manager.sar_overdue(as_of=NOW) == []


def test_sar_overdue_defaults_to_current_time(manager):
    case = _open(manager, score=90)
    case.opened_at = datetime(2000, 1, 1, tzinfo=timezone.utc)
    assert manager.sar_overdue() == [case]


# ---------------------------------------------------------------- list_open


def test_list_open_returns_only_open_and_investigating(manager):
    open_case = _open(manager, "TXN-1")
    investigating = _open(manager, "TXN-2")
    manager.assign(investigating.case_id, ANALYST)
    fraud = _open(manager, "TXN-3")
    manager.assign(fraud.case_id, ANALYST)
    manager.close(fraud.case_id, ANALYST, confirmed_fraud=True)
    false_positive = _open(manager, "TXN-4")
    manager.assign(false_positive.case_id, ANALYST)
    manager.close(false_positive.case_id, ANALYST, confirmed_fraud=False)

    assert {c.case_id for c in manager.list_open()} == {open_case.case_id, investigating.case_id}


def test_list_open_empty_manager(manager):
    assert manager.list_open() == []
