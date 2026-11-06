from __future__ import annotations

from fastapi import APIRouter, Response
from pydantic import BaseModel

from app.core.currencies import CURRENCY_MINOR_UNITS

router = APIRouter(prefix="/meta", tags=["meta"])


class CurrencyInfo(BaseModel):
    code: str
    minor_units: int


_CURRENCIES = [
    CurrencyInfo(code=code, minor_units=units)
    for code, units in sorted(CURRENCY_MINOR_UNITS.items())
]


@router.get("/currencies", response_model=list[CurrencyInfo], summary="Supported currencies")
async def list_currencies(response: Response) -> list[CurrencyInfo]:
    response.headers["Cache-Control"] = "public, max-age=86400"
    return _CURRENCIES
