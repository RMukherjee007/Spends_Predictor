package com.spendspredictor;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootApplication
public class SpendsPredictorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpendsPredictorApplication.class, args);
    }

    @Component
    public static class AppSettings {
        public static final String INSECURE_DEV_SECRET = "dev-insecure-change-me-dev-insecure-change-me";

        @Value("${app.environment:development}")
        public String environment;

        @Value("${app.default-currency:INR}")
        public String defaultCurrency;

        @Value("${app.jwt-secret:" + INSECURE_DEV_SECRET + "}")
        public String jwtSecret;

        @Value("${app.jwt-issuer:spends-predictor}")
        public String jwtIssuer;

        @Value("${app.access-token-ttl-minutes:15}")
        public int accessTokenTtlMinutes;

        @Value("${app.refresh-token-ttl-days:30}")
        public int refreshTokenTtlDays;

        @Value("${app.dev-login-enabled:false}")
        public boolean devLoginEnabled;

        @Value("${app.cookie-secure:true}")
        public boolean cookieSecure;

        @Value("${app.cors-origins:http://localhost:5173}")
        public String corsOriginsRaw;

        @Value("${app.field-encryption-keys:}")
        public String fieldEncryptionKeysRaw;

        @Value("${app.trust-proxy-headers:true}")
        public boolean trustProxyHeaders;

        public List<String> getCorsOrigins() {
            return splitCsv(corsOriginsRaw);
        }

        public List<String> getFieldEncryptionKeys() {
            return splitCsv(fieldEncryptionKeysRaw);
        }

        public boolean isProduction() {
            return "production".equalsIgnoreCase(environment);
        }

        private static List<String> splitCsv(String raw) {
            if (raw == null || raw.isBlank()) return List.of();
            return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }

        @PostConstruct
        public void validateSettings() {
            defaultCurrency = defaultCurrency != null ? defaultCurrency.toUpperCase() : "INR";
            if (!"staging".equalsIgnoreCase(environment) && !"production".equalsIgnoreCase(environment)) {
                return;
            }
            List<String> problems = new ArrayList<>();
            if (jwtSecret == null || INSECURE_DEV_SECRET.equals(jwtSecret) || jwtSecret.length() < 32) {
                problems.add("JWT_SECRET must be set to a random value of at least 32 characters");
            }
            if (getFieldEncryptionKeys().isEmpty()) {
                problems.add("FIELD_ENCRYPTION_KEYS must be set");
            }
            if (!cookieSecure) {
                problems.add("COOKIE_SECURE must be true");
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Invalid " + environment + " configuration: " + String.join("; ", problems));
            }
        }
    }

    @Component
    public static class RequestContextFilter extends OncePerRequestFilter {
        public static final String REQUEST_ID_HEADER = "X-Request-ID";
        public static final String REQUEST_ID_ATTR = "request_id";
        private static final Pattern SAFE_REQ_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

        private final AppSettings settings;

        public RequestContextFilter(AppSettings settings) {
            this.settings = settings;
        }

        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
        ) throws ServletException, IOException {
            String incoming = request.getHeader(REQUEST_ID_HEADER);
            String requestId = (incoming != null && SAFE_REQ_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
            request.setAttribute(REQUEST_ID_ATTR, requestId);
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // Security headers
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            String uri = request.getRequestURI();
            if (uri != null && uri.startsWith("/api/")) {
                response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
                );
            } else {
                response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
                );
            }
            if (settings.cookieSecure) {
                response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
            }

            // CORS handling
            String origin = request.getHeader("Origin");
            if (origin != null && settings.getCorsOrigins().contains(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-CSRF-Token, X-Request-ID");
                response.setHeader("Access-Control-Expose-Headers", "X-Request-ID");
            }
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }

            filterChain.doFilter(request, response);
        }
    }
}
