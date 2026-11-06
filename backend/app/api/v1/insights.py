from __future__ import annotations

from fastapi import APIRouter

from app.api.deps import CurrentUser, SessionDep
from app.schemas.finance import MLInsightsResponse
from app.services import finance as finance_service

router = APIRouter(prefix="/insights", tags=["insights"])


@router.get(
    "",
    response_model=MLInsightsResponse,
    summary="Get 7-pillar ML insights, probabilistic forecast, and runway",
)
async def get_insights(user: CurrentUser, session: SessionDep) -> MLInsightsResponse:
    return await finance_service.get_user_ml_insights(session, user)
