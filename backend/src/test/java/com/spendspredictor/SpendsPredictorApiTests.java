package com.spendspredictor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:spends_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.environment=test",
    "app.dev-login-enabled=true",
    "app.cookie-secure=false"
})
class SpendsPredictorApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityService securityService;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetSecurityState() {
        securityService.clearStateForTests();
    }

    private String loginMobileToken(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", "SecurePass#2026",
                    "full_name", "Asha Rao",
                    "client", "mobile"
                ))))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> body = mapper.readValue(res.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) body.get("tokens");
        return String.valueOf(tokens.get("access_token"));
    }

    @Test
    void healthSecurityHeadersAndCryptoVerification() throws Exception {
        mvc.perform(get("/api/v1/health").header("X-Request-ID", "req-test-123"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-ID", "req-test-123"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(get("/api/v1/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checks.database").value("ok"));

        mvc.perform(get("/api/v1/meta/currencies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").exists());

        // Verify AES-256-GCM (enc2:) field encryption and graceful fallback on corrupted ciphertext
        String encrypted = securityService.encryptField("4821");
        assertThat(encrypted).startsWith("enc2:");
        assertThat(securityService.decryptField(encrypted)).isEqualTo("4821");
        assertThat(securityService.decryptField("enc2:corrupted-payload")).isEqualTo("••••");
    }

    @Test
    void emailPasswordAuthVerificationAndWrongPasswordRejection() throws Exception {
        // 1. Reject password shorter than 6 characters
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"auth_user@example.com\",\"password\":\"123\",\"client\":\"mobile\"}"))
            .andExpect(status().isUnprocessableEntity());

        // 2. First login registers the user in the DB with PBKDF2-HMAC-SHA256 password hash
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"auth_user@example.com\",\"password\":\"MyStrongPassword1!\",\"full_name\":\"Auth User\",\"client\":\"mobile\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value("auth_user@example.com"));

        // 3. Subsequent login with the SAME password succeeds
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"auth_user@example.com\",\"password\":\"MyStrongPassword1!\",\"client\":\"mobile\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokens.access_token").isNotEmpty());

        // 4. Subsequent login with a WRONG password fails with 401 Unauthorized
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"auth_user@example.com\",\"password\":\"WrongPassword999!\",\"client\":\"mobile\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("not_authenticated"));
    }

    @Test
    void webCookieAuthCsrfAndRefreshTokenReuseDetection() throws Exception {
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"web@example.com\",\"full_name\":\"Web User\",\"client\":\"web\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokens").isEmpty())
            .andReturn();

        Cookie accessCookie = loginRes.getResponse().getCookie("sp_access");
        Cookie refreshCookie = loginRes.getResponse().getCookie("sp_refresh");
        Cookie csrfCookie = loginRes.getResponse().getCookie("sp_csrf");
        assertThat(accessCookie).isNotNull();
        assertThat(refreshCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        // Mutating request with cookie auth MUST fail without X-CSRF-Token
        mvc.perform(patch("/api/v1/users/me")
                .cookie(accessCookie, csrfCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"base_currency\":\"USD\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("csrf_failed"));

        // Succeeds with matching X-CSRF-Token
        mvc.perform(patch("/api/v1/users/me")
                .cookie(accessCookie, csrfCookie)
                .header("X-CSRF-Token", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"base_currency\":\"USD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.base_currency").value("USD"));

        // Rotate refresh token once
        MvcResult rot1 = mvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie, csrfCookie)
                .header("X-CSRF-Token", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie rotatedRefresh = rot1.getResponse().getCookie("sp_refresh");
        Cookie rotatedCsrf = rot1.getResponse().getCookie("sp_csrf");
        assertThat(rotatedRefresh).isNotNull();

        // Replaying the old refresh token triggers family revocation!
        mvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie, csrfCookie)
                .header("X-CSRF-Token", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());

        // Even the newly rotated token in the same family is now revoked
        mvc.perform(post("/api/v1/auth/refresh")
                .cookie(rotatedRefresh, rotatedCsrf)
                .header("X-CSRF-Token", rotatedCsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void userProfileValidationAndClearFullName() throws Exception {
        String token = loginMobileToken("profile@example.com");

        // Clear full_name to null
        mvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"full_name\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.full_name").isEmpty());

        // Reject invalid currency, timezone, and extra fields
        mvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"base_currency\":\"XYZ\"}"))
            .andExpect(status().isUnprocessableEntity());

        mvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Mars/Olympus\"}"))
            .andExpect(status().isUnprocessableEntity());

        mvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"is_admin\":true}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullFinanceAndMlInsightsPipeline() throws Exception {
        String token = loginMobileToken("finance_java@example.com");

        // 0. New user visits Dashboard/Intelligence before syncing accounts -> honest empty state (0, [])
        MvcResult previewInsRes = mvc.perform(get("/api/v1/insights")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> previewInsights = mapper.readValue(previewInsRes.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        assertThat(((Number) previewInsights.get("spent_this_month_minor")).longValue()).isEqualTo(0L);
        assertThat(((Number) previewInsights.get("forecast_p50_minor")).longValue()).isEqualTo(0L);
        assertThat((List<?>) previewInsights.get("category_forecasts")).isEmpty();
        Map<String, Object> previewAudit = (Map<String, Object>) previewInsights.get("java_audit");
        assertThat(previewAudit.get("is_valid")).isEqualTo(true);
        assertThat(String.valueOf(previewAudit.get("verifier_engine"))).startsWith("Java OpenJDK");
        assertThat(((Number) previewAudit.get("total_debits_minor")).longValue()).isEqualTo(0L);

        // 1. Sync mock bank accounts and 6 months of transactions
        MvcResult syncRes = mvc.perform(post("/api/v1/accounts/sync-mock")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        List<Map<String, Object>> accounts = mapper.readValue(syncRes.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        assertThat(accounts).hasSizeGreaterThanOrEqualTo(3);

        // 2. List transactions & test active learning category update
        MvcResult txnsRes = mvc.perform(get("/api/v1/transactions?limit=25")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        List<Map<String, Object>> txns = mapper.readValue(txnsRes.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        assertThat(txns).isNotEmpty();
        String firstTxnId = String.valueOf(txns.get(0).get("id"));

        mvc.perform(patch("/api/v1/transactions/" + firstTxnId + "/category")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"shopping\",\"apply_to_future\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("shopping"))
            .andExpect(jsonPath("$.category_source").value("user"));

        // 3. Import CSV without account_id (auto-creates CSV Statement Account) + JPY 0-decimal test
        String csv = "Date,Narration,Debit,Credit,Currency\n2026-10-20,TOKYO RAMEN BAR,1500,,JPY\n2026-10-21,CONSULTING BONUS,,250000,JPY\n";
        mvc.perform(post("/api/v1/transactions/import-csv")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("csv_content", csv))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported_count").value(2))
            .andExpect(jsonPath("$.skipped_count").value(0));

        // Re-importing the exact same CSV is idempotent (0 imported, 2 skipped)
        mvc.perform(post("/api/v1/transactions/import-csv")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("csv_content", csv))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported_count").value(0))
            .andExpect(jsonPath("$.skipped_count").value(2));

        // Cross-user IDOR isolation: another user cannot modify User A's transaction
        String otherToken = loginMobileToken("other_user@example.com");
        mvc.perform(patch("/api/v1/transactions/" + firstTxnId + "/category")
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"dining\"}"))
            .andExpect(status().isNotFound());

        // 4. Verify 7-pillar ML insights & native in-JVM BigDecimal + SHA-256 audit seal
        MvcResult insRes = mvc.perform(get("/api/v1/insights")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> insights = mapper.readValue(insRes.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        long p10 = ((Number) insights.get("forecast_p10_minor")).longValue();
        long p50 = ((Number) insights.get("forecast_p50_minor")).longValue();
        long p90 = ((Number) insights.get("forecast_p90_minor")).longValue();
        assertThat(p10).isLessThanOrEqualTo(p50);
        assertThat(p50).isLessThanOrEqualTo(p90);

        Map<String, Object> javaAudit = (Map<String, Object>) insights.get("java_audit");
        assertThat(javaAudit.get("is_valid")).isEqualTo(true);
        assertThat(((Number) javaAudit.get("total_debits_minor")).longValue()).isGreaterThan(0L);
        assertThat(String.valueOf(javaAudit.get("audit_seal_sha256"))).hasSize(64);

        // 4b. Out-of-band SQL tamper detection: alter a transaction row directly in the DB and verify is_valid == false
        Long originalBaseMinor = jdbc.queryForObject(
            "SELECT amount_base_minor FROM transactions WHERE id = ?",
            Long.class,
            java.util.UUID.fromString(firstTxnId)
        );
        jdbc.update(
            "UPDATE transactions SET amount_base_minor = amount_base_minor + 999900 WHERE id = ?",
            java.util.UUID.fromString(firstTxnId)
        );
        mvc.perform(get("/api/v1/insights")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.java_audit.is_valid").value(false));

        // Restore original value and verify audit seal passes again
        jdbc.update(
            "UPDATE transactions SET amount_base_minor = ? WHERE id = ?",
            originalBaseMinor,
            java.util.UUID.fromString(firstTxnId)
        );
        mvc.perform(get("/api/v1/insights")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.java_audit.is_valid").value(true));

        // 5. Switch base currency in Settings (INR -> USD -> JPY -> KWD) and verify LedgerSecurityVerifier stays valid
        for (String targetCurr : List.of("USD", "JPY", "KWD")) {
            mvc.perform(patch("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"base_currency\":\"" + targetCurr + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_currency").value(targetCurr));

            MvcResult currInsRes = mvc.perform(get("/api/v1/insights")
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value(targetCurr))
                .andExpect(jsonPath("$.java_audit.is_valid").value(true))
                .andReturn();
            Map<String, Object> currIns = mapper.readValue(currInsRes.getResponse().getContentAsByteArray(), new TypeReference<>() {});
            Map<String, Object> currAudit = (Map<String, Object>) currIns.get("java_audit");
            assertThat(((Number) currAudit.get("total_debits_minor")).longValue()).isGreaterThan(0L);
        }

        // 6. Logout revokes Bearer access token jti immediately
        mvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void quantCoreKernelAndThymeleafHtmxUiFlow() throws Exception {
        // 1. Direct unit verification of in-JVM Java 21 QuantCoreKernel
        double waldProb = QuantCoreKernel.waldFirstPassageProb(500_000.0, 100_000.0, -2_500.0, 1_200.0, 15.0);
        assertThat(waldProb).isBetween(0.0, 1.0);

        QuantCoreKernel.MonteCarloResult mc = QuantCoreKernel.runMonteCarloRunway(
            500_000L,
            100_000L,
            new double[] {12_000.0, 15_000.0, 8_000.0, 22_000.0},
            new double[] {0.0, 0.0, -50_000.0, 0.0, 0.0},
            5,
            2000,
            20261027L
        );
        assertThat(mc.p10ByDay()).hasSize(6);
        assertThat(mc.p10ByDay()[5]).isLessThanOrEqualTo(mc.p50ByDay()[5]);
        assertThat(mc.p50ByDay()[5]).isLessThanOrEqualTo(mc.p90ByDay()[5]);
        assertThat(mc.engine()).isEqualTo(QuantCoreKernel.ENGINE_NAME);

        QuantCoreKernel.MinTResult mint = QuantCoreKernel.mintReconcile(
            new double[] {100.0, 200.0, 300.0},
            new double[] {10.0, 20.0, 30.0},
            660.0,
            15.0
        );
        assertThat(mint.totalReconciled()).isGreaterThan(600L);

        double cusum = QuantCoreKernel.pageCusum(
            new double[] {1000.0, 1100.0, 1500.0, 1800.0, 2200.0},
            1000.0,
            200.0,
            0.5
        );
        assertThat(cusum).isGreaterThan(5.0);

        // 2. Thymeleaf + HTMX Web UI Flow
        // Unauthenticated GET / redirects to /login
        mvc.perform(get("/"))
            .andExpect(status().is3xxRedirection());

        // GET /login renders Thymeleaf sign-in page
        MvcResult loginPageRes = mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(loginPageRes.getResponse().getContentAsString()).contains("Sign in to Spends Predictor");

        // POST /ui/login signs in and sets cookies
        MvcResult uiLoginRes = mvc.perform(post("/ui/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "thymeleaf_ui@example.com")
                .param("password", "UiPassword123!")
                .param("full_name", "Raghav UI"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        Cookie accessCookie = uiLoginRes.getResponse().getCookie("sp_access");
        Cookie refreshCookie = uiLoginRes.getResponse().getCookie("sp_refresh");
        Cookie csrfCookie = uiLoginRes.getResponse().getCookie("sp_csrf");
        assertThat(accessCookie).isNotNull();

        // GET / renders Dashboard (honest empty state before sync)
        MvcResult dashEmptyRes = mvc.perform(get("/").cookie(accessCookie, refreshCookie, csrfCookie))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(dashEmptyRes.getResponse().getContentAsString())
            .contains("Cashflow &amp; Spend Forecast")
            .contains("No transactions in your ledger yet");

        // POST /ui/accounts/sync populates sandbox accounts and transactions
        mvc.perform(post("/ui/accounts/sync")
                .cookie(accessCookie, refreshCookie, csrfCookie)
                .param("redirect", "/accounts"))
            .andExpect(status().is3xxRedirection());

        // GET /accounts renders connected institutions and AES-256-GCM badge
        MvcResult acctsUiRes = mvc.perform(get("/accounts").cookie(accessCookie, refreshCookie, csrfCookie))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(acctsUiRes.getResponse().getContentAsString())
            .contains("AES-256-GCM")
            .contains("Active Financial Institutions");

        // GET /intelligence renders 7-Pillar ML Intelligence page with Java 21 QuantCore
        MvcResult intelUiRes = mvc.perform(get("/intelligence").cookie(accessCookie, refreshCookie, csrfCookie))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(intelUiRes.getResponse().getContentAsString())
            .contains("Java 21 In-JVM QuantCore")
            .contains("Hierarchical Category Forecast (MinT Reconciled)");

        // HTMX partial swap: GET /intelligence?tab=runway with HX-Request: true returns only the tab fragment
        MvcResult htmxTabRes = mvc.perform(get("/intelligence?tab=runway")
                .cookie(accessCookie, refreshCookie, csrfCookie)
                .header("HX-Request", "true"))
            .andExpect(status().isOk())
            .andReturn();
        String tabHtml = htmxTabRes.getResponse().getContentAsString();
        assertThat(tabHtml)
            .contains("30-Day Liquidity Runway Simulation")
            .doesNotContain("<!DOCTYPE html>");

        // GET /transactions renders Transactions Ledger
        MvcResult txnsUiRes = mvc.perform(get("/transactions").cookie(accessCookie, refreshCookie, csrfCookie))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(txnsUiRes.getResponse().getContentAsString()).contains("Transactions Ledger");
    }
}
