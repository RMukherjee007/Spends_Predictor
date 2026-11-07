# Spends Predictor

Production-grade polyglot personal & commercial spend forecasting platform. Built web-first with a versioned, mobile-ready REST API (`/api/v1`) powered by **Java 21+ (Spring Boot 3)**, a **Python `scikit-learn` ML engine**, a **C++20 `-O3` native quantitative kernel (`libquantcore`)**, and a **React 19 + TypeScript** frontend.

---

## Architecture Highlights

- **Primary Backend API (`backend/src/main/java/com/spendspredictor/`)**:
  - **Java 21+ & Spring Boot 3.2**: Serves all `/api/v1/*` REST endpoints (`health`, `auth`, `users`, `accounts`, `transactions`, `insights`), request-ID & CSP/HSTS security headers filter, sliding-window rate limiting, and JDBC persistence (PostgreSQL in production, zero-config embedded PostgreSQL-compatibility mode locally).
  - **In-JVM `LedgerSecurityVerifier`**: Executes directly inside the Spring Boot JVM using `java.math.BigDecimal` (`MathContext.DECIMAL128`, 34 decimal digits, `RoundingMode.HALF_EVEN` banker's rounding) and builds a cumulative `SHA-256` Merkle-style hash-chain seal over every transaction record.
- **Dual-Mode Auth & Field Encryption**:
  - **Web client**: `httpOnly` access + rotating refresh cookies (`sp_access`, `sp_refresh`) + double-submit CSRF cookie/header (`sp_csrf` / `X-CSRF-Token`).
  - **Mobile/Bearer client**: `Authorization: Bearer` access tokens + rotating refresh tokens with token-family reuse/theft detection.
  - **Application-Level Encryption**: Multi-key `AES-CBC-PKCS5Padding` + `HMAC-SHA256` encryption at rest for sensitive account fields.
- **Multi-Currency Money Engine**: 18+ active ISO-4217 currencies with exact minor-unit exponents (`0` for `JPY`, `2` for `INR`/`USD`, `3` for `KWD`). Money is stored strictly in 64-bit integer minor units (paise/cents), never floats.
- **7-Pillar ML & C++20 Quantitative Engine (`backend/app/`)**:
  - **Python `scikit-learn` (`ml_engine.py`, `categorizer.py`, `ml_cli.py`)**: Ridge / Huber / GradientBoosting / Holt-Winters walk-forward forecasting, TF-IDF + LogisticRegression + Active Learning categorization, IsolationForest + MAD anomaly detection, K-Means + PCA behavioral personas, and exact linear SHAP attributions.
  - **C++20 Native Kernel (`native/quantcore.cpp`)**: Compiled with `-std=c++20 -O3` for 10,000-path Xorshift64* Monte Carlo liquidity runway simulations, closed-form Bachelier-Wald Brownian barrier-crossing probabilities, Page's CUSUM drift statistics, and Wickramasuriya MinT variance-weighted hierarchical forecast reconciliation.
- **Frontend (`frontend/`)**: React 19 + TypeScript + Vite + Tailwind CSS v4, end-to-end typed OpenAPI client (`openapi-typescript` + `openapi-fetch`), automatic light/dark mode, and interactive SVG quantitative charts.

---

## Quickstart (No External Keys or Docker Required)

### 1. Start the Java Spring Boot 3 Backend API

```bash
cd backend
mvn spring-boot:run
```

- Health check: `http://localhost:8000/api/v1/health`
- Readiness check (DB + C++20 kernel + JVM BigDecimal verifier): `http://localhost:8000/api/v1/health/ready`

### 2. Start the Frontend Web App

```bash
cd frontend
cp .env.example .env.local
npm run dev
```

Open `http://localhost:5173` and click **Sign in** to log in immediately with the local development account, sync sample Indian & international transactions, upload CSV bank statements, or run what-if Monte Carlo runway simulations.

---

## Quality Checks & Tests

```bash
# 1. Java Spring Boot 3 API Integration Tests (MockMvc + H2 PostgreSQL mode + Python ML + C++20)
cd backend
mvn test

# 2. Python ML Engine & C++20 Native Kernel Checks (Ruff, strict Mypy, Pytest)
.venv/bin/ruff check app tests
.venv/bin/mypy app tests
.venv/bin/pytest -v

# 3. Frontend: ESLint, TypeScript, Vitest, and Vite production build
cd ../frontend
npm run lint
npm run typecheck
npm test -- --run
npm run build
```
