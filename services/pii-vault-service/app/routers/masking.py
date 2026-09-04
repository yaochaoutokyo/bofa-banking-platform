from fastapi import APIRouter, HTTPException

from app.models.pii import BulkMaskRequest, MaskRequest, MaskResponse
from app.services import masking
from app.services.vault import _NORMALIZERS
from app.services.validators import PiiValidationError

router = APIRouter(prefix="/api/v1/mask", tags=["masking"])


@router.post("", response_model=MaskResponse)
def mask_value(request: MaskRequest) -> MaskResponse:
    try:
        normalized = _NORMALIZERS[request.pii_type](request.value)
    except PiiValidationError as exc:
        raise HTTPException(status_code=422, detail={"code": exc.code, "message": exc.message}) from exc
    return MaskResponse(masked=masking.mask(request.pii_type, normalized))


@router.post("/record")
def mask_record(request: BulkMaskRequest) -> dict[str, str]:
    return masking.mask_record(request.record, request.field_types)
