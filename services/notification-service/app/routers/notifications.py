from fastapi import APIRouter, HTTPException

from app.models.notification import Delivery, SendRequest
from app.services.dispatcher import DispatchError, dispatcher

router = APIRouter(prefix="/api/v1/notifications", tags=["notifications"])


@router.post("", response_model=Delivery, status_code=201)
def send(request: SendRequest) -> Delivery:
    try:
        return dispatcher.send(request)
    except DispatchError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.get("/{customer_id}", response_model=list[Delivery])
def history(customer_id: str, limit: int = 50) -> list[Delivery]:
    return dispatcher.history(customer_id, limit)


@router.post("/release-queued")
def release_queued() -> dict[str, int]:
    return {"released": dispatcher.release_quiet_hours_queue()}
