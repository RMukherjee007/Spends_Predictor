"""Domain errors. Translated to a consistent JSON error envelope in `app.main`."""

from __future__ import annotations


class AppError(Exception):
    status_code = 400
    code = "bad_request"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.code)
        self.message = message or self.code.replace("_", " ").capitalize()


class AuthenticationError(AppError):
    status_code = 401
    code = "not_authenticated"


class PermissionDeniedError(AppError):
    status_code = 403
    code = "permission_denied"


class CSRFError(AppError):
    status_code = 403
    code = "csrf_failed"


class NotFoundError(AppError):
    status_code = 404
    code = "not_found"


class RateLimitError(AppError):
    status_code = 429
    code = "rate_limited"
