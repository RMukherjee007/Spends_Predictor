# Spends Predictor

Production-grade personal & commercial spend forecasting platform. Built web-first with a versioned, mobile-ready API (`/api/v1`) so native iOS/Android clients can share the exact same backend and OpenAPI contract.

---

## Architecture Highlights (Phase 0 Foundation)

- **Backend (`backend/`)**: FastAPI, SQLAlchemy 2 (async), Alembic migrations, Pydantic v2 settings, Structlog JSON/console logging, request-ID & security-headers ASGI middleware, ARQ + Redis background worker, and Sentry error reporting (with `send_default_pii=False`).
- **Dual-Mode Auth**:
  - **Web client**: `httpOnly` access + rotating refresh cookies (`sp_access`, `sp_refresh`) + double-submit CSRF cookie/header (`sp_csrf` / `X-CSRF-Token`).
  - **Future Mobile client**: `Authorization: Bearer` access tokens + rotating refresh tokens with reuse/theft detection that revokes the entire token family.
- **Application-Level Encryption**: `EncryptedString` SQLAlchemy type using `MultiFernet` (AES-128-CBC + HMAC-SHA256) with multi-key rotation for sensitive bank/account fields.
- **Multi-Currency Money Engine**: All 150+ active ISO-4217 currencies with exact minor-unit metadata (e.g., 2 decimals for `INR`/`USD`, 0 for `JPY`, 3 for `KWD`). Money is always stored in integer minor units (paise/cents), never floats.
- **Frontend (`frontend/`)**: React 19 + TypeScript + Vite + Tailwind CSS v4, end-to-end typed OpenAPI client (`openapi-typescript` + `openapi-fetch`), automatic light/dark mode (`color-scheme: light dark` + `light-dark()`), Forced Colors Mode support, and container-query responsive layout.

---

## Quickstart (No External Keys or Docker Required)

By default, local development uses SQLite and enables passwordless Dev Login so you can run the full stack immediately:

### 1. Start the Backend API

```bash
cd backend
cp .env.example .env
.venv/bin/alembic upgrade head
.venv/bin/uvicorn app.main:app --reload --port 8000
```

- API docs (Swagger UI): `http://localhost:8000/docs`
- Health check: `http://localhost:8000/api/v1/health`

### 2. Start the Frontend Web App

```bash
cd frontend
cp .env.example .env.local
npm run dev
```

Open `http://localhost:5173` and click **Sign in** to log in immediately with the local development account, view the dashboard, and update your base currency or timezone in **Settings**.

---

## Running with Postgres & Redis (Docker Compose)

When Docker Desktop is running:

```bash
docker compose up --build
```

---

## Quality Checks & Tests

```bash
# Backend: Ruff lint, format check, strict Mypy, and Pytest (24 tests)
cd backend
.venv/bin/ruff check .
.venv/bin/mypy app tests
.venv/bin/pytest -v

# Regenerate OpenAPI schema + frontend TypeScript types
.venv/bin/python -m app.scripts.export_openapi ../frontend/openapi.json
cd ../frontend && npm run gen:api

# Frontend: ESLint, TypeScript, Vitest, and Vite production build
npm run lint
npm run typecheck
npm test
npm run build
```

---

## External Accounts Setup Checklist (When Ready for Production)

You do **not** need any of these for local development, but when you are ready to deploy publicly:

1. **Encryption Key (`FIELD_ENCRYPTION_KEYS`)**:
   - Run `backend/.venv/bin/python -m app.scripts.generate_key` to generate a 32-byte URL-safe Fernet key.
2. **Google Sign-In (`GOOGLE_CLIENT_IDS` / `VITE_GOOGLE_CLIENT_ID`)**:
   - Create a project in [Google Cloud Console](https://console.cloud.google.com/).
   - Go to **APIs & Services → Credentials → Create Credentials → OAuth client ID** (Web application).
   - Add `http://localhost:5173` and your Render frontend URL to **Authorized JavaScript origins**.
   - Paste the Client ID into `GOOGLE_CLIENT_IDS` (backend) and `VITE_GOOGLE_CLIENT_ID` (frontend).
3. **Render Deployment (`render.yaml`)**:
   - Push this repository to GitHub.
   - In [Render](https://dashboard.render.com/), click **New → Blueprint** and select your repo.
   - Render will automatically provision the free PostgreSQL database, Key-Value store, FastAPI web service, and React static site using `render.yaml`.
   - Note: Render formats Postgres URLs as `postgres://...`; if needed, prefix with `postgresql+asyncpg://` in the `DATABASE_URL` env var.
4. **Sentry Error Monitoring (Optional)**:
   - Create a free account at [sentry.io](https://sentry.io/), create a **FastAPI** project and a **React** project, and set `SENTRY_DSN` (backend) and `VITE_SENTRY_DSN` (frontend).
