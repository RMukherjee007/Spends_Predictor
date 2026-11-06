from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app import __version__
from app.api.deps import SessionDep
from app.schemas.common import HealthResponse

router = APIRouter(prefix="/health", tags=["health"])


@router.get("", response_model=HealthResponse, summary="Liveness probe")
async def liveness() -> HealthResponse:
    return HealthResponse(status="ok", version=__version__)


@router.get(
    "/ready",
    response_model=HealthResponse,
    responses={503: {"model": HealthResponse}},
    summary="Readiness probe (checks dependencies)",
)
async def readiness(session: SessionDep) -> HealthResponse | JSONResponse:
    checks: dict[str, str] = {}
    try:
        await session.execute(text("SELECT 1"))
        checks["database"] = "ok"
    except Exception:  # pragma: no cover - exercised only when the DB is down
        checks["database"] = "unavailable"

    if all(v == "ok" for v in checks.values()):
        return HealthResponse(status="ok", version=__version__, checks=checks)
    body = HealthResponse(status="degraded", version=__version__, checks=checks)
    return JSONResponse(status_code=503, content=body.model_dump())
