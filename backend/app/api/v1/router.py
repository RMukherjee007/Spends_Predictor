from __future__ import annotations

from fastapi import APIRouter

from app.api.v1 import accounts, auth, health, insights, meta, transactions, users

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(meta.router)
api_router.include_router(auth.router)
api_router.include_router(users.router)
api_router.include_router(accounts.router)
api_router.include_router(transactions.router)
api_router.include_router(insights.router)
