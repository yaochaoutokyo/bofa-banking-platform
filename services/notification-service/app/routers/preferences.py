from fastapi import APIRouter, HTTPException

from app.models.notification import Preferences
from app.services.dispatcher import DispatchError, store

router = APIRouter(prefix="/api/v1/preferences", tags=["preferences"])


@router.get("/{customer_id}", response_model=Preferences)
def get(customer_id: str) -> Preferences:
    try:
        return store.get(customer_id)
    except DispatchError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc


@router.put("", response_model=Preferences)
def upsert(prefs: Preferences) -> Preferences:
    try:
        return store.upsert(prefs)
    except DispatchError as exc:
        raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": exc.message}) from exc
