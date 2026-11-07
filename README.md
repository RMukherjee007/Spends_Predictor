# Spends Predictor

Production-grade full-stack personal & commercial spend forecasting platform powered by **Java 21+ (Spring Boot 3.2)**, **Thymeleaf + HTMX** server-rendered hypermedia UI, an in-JVM **Java 21 `QuantCoreKernel`** (Monte Carlo liquidity runway & Bachelier-Wald barrier equations), an in-JVM **`LedgerSecurityVerifier`** (`DECIMAL128` + `SHA-256` tamper-evident audit seal), and a persistent **Python `scikit-learn` ML worker**.

---

## Architecture Highlights

- **Full-Stack Java 21 & Spring Boot 3.2 (`backend/src/main/java/com/spendspredictor/`)**:
  - **Web UI (`WebUiController.java` + `src/main/resources/templates/`)**: Server-rendered **Thymeleaf + HTMX** interface styled with Tailwind CSS (`src/main/resources/static/css/app.css`). Eliminates Node.js, `npm`, and Vite completely — the entire web application and REST API package into a single executable `.jar`.
  - **REST API (`ApiControllers.java`)**: Serves all `/api/v1/*` endpoints (`health`, `auth`, `users`, `accounts`, `transactions`, `insights`), request-ID & CSP/HSTS security headers filter, proxy-aware rate limiting, and JDBC persistence with versioned schema migrations (`schema_migrations`) and composite indexes.
  - **In-JVM `QuantCoreKernel.java`**: Replaces external C++ shared libraries with zero-allocation primitive loops compiled by the HotSpot C2 JIT compiler for 10,000-path SplitMix64 Monte Carlo liquidity runway simulations, closed-form Bachelier-Wald Brownian motion barrier-crossing probabilities, Page's CUSUM drift statistics, and Minimum Trace (MinT) hierarchical forecast reconciliation.
  - **In-JVM `LedgerSecurityVerifier.java`**: Executes directly inside the JVM using `java.math.BigDecimal` (`MathContext.DECIMAL128`, 34 decimal digits, `RoundingMode.HALF_EVEN` banker's rounding) and verifies a persisted `SHA-256` root over every transaction record to detect out-of-band SQL tampering.
- **Security & Field Encryption (`SecurityService.java`)**:
  - **Authentication**: Database-indexed Email + Password authentication (`PBKDF2WithHmacSHA256`, 65,536 iterations, constant-time verification).
  - **Session Lifecycle**: `httpOnly` access + atomic rotating refresh cookies (`sp_access`, `sp_refresh`) with token-family reuse detection and double-submit CSRF protection (`sp_csrf` / `X-CSRF-Token`).
  - **Encryption at Rest**: `PBKDF2-HMAC-SHA256` + `AES-256-GCM` (`enc2:`) authenticated field encryption for sensitive account identifiers.
- **Multi-Currency Money Engine**: ISO-4217 currencies with exact minor-unit exponents (`0` for `JPY`, `2` for `INR`/`USD`, `3` for `KWD`). Money is stored strictly in 64-bit integer minor units (paise/cents/fils), never floats.
- **Persistent 7-Pillar Python ML Worker (`backend/app/`)**:
  - Pre-warmed background daemon (`python -m app.ml_cli worker`) managed by Spring Boot for decomposed quantile regression forecasting, TF-IDF + LogisticRegression active-learning categorization, IsolationForest + MAD anomaly detection, K-Means + PCA behavioral personas, and linear SHAP attributions.

---

## Quickstart

```bash
cd backend
mvn spring-boot:run
```

Open `http://localhost:8000` in your browser:
- **Web UI**: `http://localhost:8000` (Sign in, sync sandbox accounts, inspect the 7-Pillar ML Intelligence tabs via HTMX, or import CSV bank statements)
- **Liveness check**: `http://localhost:8000/api/v1/health`
- **Readiness check**: `http://localhost:8000/api/v1/health/ready`

---

## Quality Checks & Tests

```bash
# 1. Java 21 Spring Boot Full-Stack Tests (REST API + Thymeleaf/HTMX UI + QuantCoreKernel + Ledger Audit)
cd backend
mvn test

# 2. Python ML Engine Checks (Ruff, strict Mypy, Pytest)
.venv/bin/ruff check app tests
.venv/bin/mypy app tests
.venv/bin/pytest -v
```
