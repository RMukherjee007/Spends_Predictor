"""Background worker (ARQ + Redis).

Run with: `arq app.worker.WorkerSettings`

Phase 1+ will add bank sync, exchange-rate refresh, model training and alert evaluation jobs.
"""

from __future__ import annotations

from typing import Any, ClassVar

from arq import cron
from arq.connections import RedisSettings

from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger

logger = get_logger(__name__)


async def heartbeat(ctx: dict[str, Any]) -> str:
    logger.info("worker.heartbeat")
    return "ok"


async def startup(ctx: dict[str, Any]) -> None:
    configure_logging(get_settings())
    logger.info("worker.startup")


async def shutdown(ctx: dict[str, Any]) -> None:
    logger.info("worker.shutdown")


class WorkerSettings:
    functions: ClassVar[list[Any]] = [heartbeat]
    cron_jobs: ClassVar[list[Any]] = [cron(heartbeat, minute={0, 15, 30, 45})]
    on_startup = startup
    on_shutdown = shutdown
    redis_settings = RedisSettings.from_dsn(get_settings().redis_url)
    max_jobs = 10
    job_timeout = 300
