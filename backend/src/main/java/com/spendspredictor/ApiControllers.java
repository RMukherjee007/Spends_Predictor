package com.spendspredictor;

import com.spendspredictor.ApiModels.AppException;
import com.spendspredictor.ApiModels.AuthResponse;
import com.spendspredictor.ApiModels.BankAccountRead;
import com.spendspredictor.ApiModels.CsvImportRequest;
import com.spendspredictor.ApiModels.CsvImportResponse;
import com.spendspredictor.ApiModels.CurrencyInfo;
import com.spendspredictor.ApiModels.ErrorDetail;
import com.spendspredictor.ApiModels.ErrorEnvelope;
import com.spendspredictor.ApiModels.LoginRequest;
import com.spendspredictor.ApiModels.RefreshRequest;
import com.spendspredictor.ApiModels.TokenPair;
import com.spendspredictor.ApiModels.TransactionRead;
import com.spendspredictor.ApiModels.TransactionUpdateCategory;
import com.spendspredictor.ApiModels.UserRead;
import com.spendspredictor.FinanceService.RotatedSession;
import com.spendspredictor.SecurityService.AccessTokenClaims;
import com.spendspredictor.SecurityService.IssuedTokens;
import com.spendspredictor.SpendsPredictorApplication.AppSettings;
import com.spendspredictor.SpendsPredictorApplication.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestController
@RequestMapping("/api/v1")
public class ApiControllers {
    private final FinanceService finance;
    private final SecurityService security;
    private final AppSettings settings;

    public ApiControllers(FinanceService finance, SecurityService security, AppSettings settings) {
        this.finance = finance;
        this.security = security;
        this.settings = settings;
    }

    private UserRead requireUser(HttpServletRequest request) {
        UUID userId = security.authenticateRequest(request);
        return finance.getUserById(userId);
    }

    private AuthResponse buildAuthResponse(HttpServletResponse response, UserRead user, IssuedTokens tokens, String client) {
        String c = client != null ? client : "web";
        if ("web".equalsIgnoreCase(c)) {
            security.setAuthCookies(response, tokens);
            return new AuthResponse(user, null);
        }
        return new AuthResponse(
            user,
            new TokenPair(tokens.accessToken(), tokens.refreshToken(), "bearer", tokens.accessExpiresIn())
        );
    }

    @GetMapping("/health")
    public Map<String, Object> liveness() {
        return Map.of(
            "status", "ok",
            "version", "0.2.0",
            "environment", settings.environment,
            "runtime", "Java Spring Boot 3 (OpenJDK " + System.getProperty("java.version") + ")"
        );
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean dbOk = finance.checkDatabaseReady();
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", dbOk ? "ok" : "error: database unreachable");
        checks.put("java_verifier", "ok (in-JVM BigDecimal DECIMAL128)");
        return ResponseEntity.status(dbOk ? 200 : 503).body(Map.of(
            "status", dbOk ? "ok" : "degraded",
            "version", "0.2.0",
            "environment", settings.environment,
            "checks", checks
        ));
    }

    @GetMapping("/meta/currencies")
    public List<CurrencyInfo> listCurrencies() {
        return ApiModels.CURRENCY_MINOR_UNITS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .map(e -> new CurrencyInfo(e.getKey(), e.getValue()))
            .toList();
    }

    @PostMapping({"/auth/login", "/auth/dev-login"})
    public AuthResponse login(
        @RequestBody LoginRequest payload,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (request.getRequestURI().endsWith("/dev-login") && !settings.devLoginEnabled) {
            throw new AppException(404, "not_found", "Resource not found");
        }
        if (payload == null || payload.email() == null || !payload.email().contains("@")) {
            throw new AppException(422, "validation_error", "Valid email is required");
        }
        String client = payload.client() != null ? payload.client() : "web";
        if (!"web".equals(client) && !"mobile".equals(client)) {
            throw new AppException(422, "validation_error", "Invalid client type");
        }
        String ip = security.resolveClientIp(request);
        security.checkRateLimit("auth", ip, 20, 60.0);

        UserRead user = finance.authenticateOrRegisterUser(payload.email(), payload.password(), payload.fullName());
        IssuedTokens tokens = finance.issueTokens(user.id(), client, request.getHeader("User-Agent"), null);
        return buildAuthResponse(response, user, tokens, client);
    }

    @PostMapping("/auth/refresh")
    public AuthResponse refresh(
        @RequestBody(required = false) RefreshRequest payload,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String cookieToken = security.getCookieValue(request, SecurityService.REFRESH_COOKIE);
        String rawToken;
        if (cookieToken != null && !cookieToken.isBlank()) {
            security.enforceCsrf(request);
            rawToken = cookieToken;
        } else if (payload != null && payload.refreshToken() != null && !payload.refreshToken().isBlank()) {
            rawToken = payload.refreshToken();
        } else {
            throw new AppException(401, "not_authenticated", "Missing refresh token");
        }

        RotatedSession rotated = finance.rotateRefreshToken(rawToken, request.getHeader("User-Agent"));
        return buildAuthResponse(response, rotated.user(), rotated.tokens(), rotated.client());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
        @RequestBody(required = false) RefreshRequest payload,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String cookieRefresh = security.getCookieValue(request, SecurityService.REFRESH_COOKIE);
        String cookieAccess = security.getCookieValue(request, SecurityService.ACCESS_COOKIE);
        if ((cookieRefresh != null && !cookieRefresh.isBlank()) || (cookieAccess != null && !cookieAccess.isBlank())) {
            security.enforceCsrf(request);
        }

        String rawRefresh = (cookieRefresh != null && !cookieRefresh.isBlank())
            ? cookieRefresh
            : (payload != null ? payload.refreshToken() : null);
        if (rawRefresh != null) {
            finance.revokeRefreshToken(rawRefresh);
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String bearerAccess = (authHeader != null && authHeader.toLowerCase().startsWith("bearer "))
            ? authHeader.substring(7).trim()
            : null;
        String rawAccess = cookieAccess != null ? cookieAccess : bearerAccess;
        if (rawAccess != null && !rawAccess.isBlank()) {
            try {
                AccessTokenClaims claims = security.decodeAccessToken(rawAccess);
                security.revokeAccessJti(claims.jti());
            } catch (Exception ignored) {
                // Ignore expired token on logout
            }
        }

        security.clearAuthCookies(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me")
    public UserRead readMe(HttpServletRequest request) {
        return requireUser(request);
    }

    @PatchMapping("/users/me")
    public UserRead updateMe(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        UserRead user = requireUser(request);
        return finance.updateUser(user.id(), body != null ? body : Map.of());
    }

    @GetMapping("/accounts")
    public List<BankAccountRead> listAccounts(HttpServletRequest request) {
        UserRead user = requireUser(request);
        return finance.listAccounts(user);
    }

    @PostMapping("/accounts/sync-mock")
    public List<BankAccountRead> syncMockAccounts(HttpServletRequest request) {
        if (settings.isProduction() && !settings.devLoginEnabled) {
            throw new AppException(404, "not_found", "Resource not found");
        }
        UserRead user = requireUser(request);
        security.checkRateLimit("sync_mock", user.id().toString(), 10, 60.0);
        return finance.syncMockBankData(user);
    }

    @GetMapping("/transactions")
    public List<TransactionRead> listTransactions(
        HttpServletRequest request,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(required = false) String category,
        @RequestParam(name = "account_id", required = false) UUID accountId
    ) {
        if (limit < 1 || limit > 500 || offset < 0) {
            throw new AppException(422, "validation_error", "Invalid pagination parameters");
        }
        UserRead user = requireUser(request);
        return finance.listTransactions(user, limit, offset, category, accountId);
    }

    @PatchMapping("/transactions/{transactionId}/category")
    public TransactionRead updateCategory(
        @PathVariable UUID transactionId,
        @RequestBody TransactionUpdateCategory payload,
        HttpServletRequest request
    ) {
        UserRead user = requireUser(request);
        if (payload == null || payload.category() == null) {
            throw new AppException(422, "validation_error", "category is required");
        }
        boolean applyToFuture = payload.applyToFuture() == null || payload.applyToFuture();
        return finance.updateTransactionCategory(user, transactionId, payload.category(), applyToFuture);
    }

    @PostMapping("/transactions/import-csv")
    public CsvImportResponse importCsv(
        @RequestBody CsvImportRequest payload,
        HttpServletRequest request
    ) {
        UserRead user = requireUser(request);
        security.checkRateLimit("csv_import", user.id().toString(), 15, 60.0);
        if (payload == null) {
            throw new AppException(422, "validation_error", "Request body required");
        }
        return finance.importCsvStatement(user, payload.accountId(), payload.csvContent());
    }

    @GetMapping("/insights")
    public Map<String, Object> getInsights(HttpServletRequest request) {
        UserRead user = requireUser(request);
        security.checkRateLimit("insights", user.id().toString(), 30, 60.0);
        return finance.getUserMlInsights(user);
    }

    @RestControllerAdvice
    public static class GlobalErrorHandler {
        private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

        private String reqId(HttpServletRequest req) {
            Object v = req.getAttribute(RequestContextFilter.REQUEST_ID_ATTR);
            return v != null ? String.valueOf(v) : null;
        }

        @ExceptionHandler(AppException.class)
        public ResponseEntity<ErrorEnvelope> handleAppException(AppException ex, HttpServletRequest req) {
            return ResponseEntity.status(ex.statusCode)
                .body(new ErrorEnvelope(new ErrorDetail(ex.code, ex.getMessage(), reqId(req), ex.details)));
        }

        @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
        })
        public ResponseEntity<ErrorEnvelope> handleValidationException(Exception ex, HttpServletRequest req) {
            return ResponseEntity.status(422)
                .body(new ErrorEnvelope(new ErrorDetail("validation_error", "Invalid request", reqId(req), null)));
        }

        @ExceptionHandler(NoResourceFoundException.class)
        public ResponseEntity<ErrorEnvelope> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
            return ResponseEntity.status(404)
                .body(new ErrorEnvelope(new ErrorDetail("not_found", "Resource not found", reqId(req), null)));
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<ErrorEnvelope> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
            return ResponseEntity.status(405)
                .body(new ErrorEnvelope(new ErrorDetail("method_not_allowed", ex.getMessage(), reqId(req), null)));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex, HttpServletRequest req) {
            log.error("Unhandled exception [request_id={}]: {}", reqId(req), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorEnvelope(new ErrorDetail("internal_error", "Something went wrong", reqId(req), null)));
        }
    }
}
