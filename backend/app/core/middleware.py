"""Request-scoped middleware: request IDs, access logging, security headers."""

from __future__ import annotations

import re
import time
import uuid

import structlog
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import get_logger

REQUEST_ID_HEADER = "X-Request-ID"
_VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

access_logger = get_logger("app.access")

SECURITY_HEADERS: dict[bytes, bytes] = {
    b"x-content-type-options": b"nosniff",
    b"x-frame-options": b"DENY",
    b"referrer-policy": b"strict-origin-when-cross-origin",
    b"cross-origin-opener-policy": b"same-origin-allow-popups",
    b"cross-origin-resource-policy": b"same-origin",
    b"permissions-policy": b"camera=(), microphone=(), geolocation=()",
    b"connection-allowlist": b'(response-origin "https://accounts.google.com")',
    b"content-security-policy": (
        b"default-src 'self'; "
        b"script-src 'self' https://accounts.google.com; "
        b"style-src 'self' 'unsafe-inline'; "
        b"img-src 'self' data: https:; "
        b"connect-src 'self' https://accounts.google.com; "
        b"frame-src https://accounts.google.com; "
        b"form-action 'self'; "
        b"worker-src 'self'; "
        b"object-src 'none'; "
        b"frame-ancestors 'none'; "
        b"base-uri 'self'"
    ),
}


class RequestContextMiddleware:
    """Pure ASGI middleware (no BaseHTTPMiddleware overhead or contextvar issues)."""

    def __init__(self, app: ASGIApp, *, hsts: bool) -> None:
        self.app = app
        self.hsts = hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        incoming = dict(scope["headers"]).get(REQUEST_ID_HEADER.lower().encode(), b"").decode()
        request_id = incoming if _VALID_REQUEST_ID.match(incoming) else uuid.uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id

        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(request_id=request_id)

        start = time.perf_counter()
        status_code = 500

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
                headers = list(message.get("headers", []))
                headers.append((REQUEST_ID_HEADER.lower().encode(), request_id.encode()))
                headers.extend(SECURITY_HEADERS.items())
                if self.hsts:
                    headers.append(
                        (b"strict-transport-security", b"max-age=63072000; includeSubDomains")
                    )
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            path = scope.get("path", "")
            if not path.startswith("/api/v1/health"):
                access_logger.info(
                    "http.request",
                    method=scope.get("method"),
                    path=path,
                    status=status_code,
                    duration_ms=round((time.perf_counter() - start) * 1000, 1),
                )
