package com.spendspredictor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spendspredictor.ApiModels.AppException;
import com.spendspredictor.ApiModels.BankAccountRead;
import com.spendspredictor.ApiModels.CsvImportResponse;
import com.spendspredictor.ApiModels.FxConversion;
import com.spendspredictor.ApiModels.TransactionRead;
import com.spendspredictor.ApiModels.UserRead;
import com.spendspredictor.LedgerSecurityVerifier.TransactionRecord;
import com.spendspredictor.LedgerSecurityVerifier.VerificationResult;
import com.spendspredictor.SecurityService.IssuedTokens;
import com.spendspredictor.SpendsPredictorApplication.AppSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
    private static final Logger log = LoggerFactory.getLogger(FinanceService.class);

    private final JdbcTemplate jdbc;
    private final AppSettings settings;
    private final SecurityService security;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Object workerLock = new Object();
    private Process pythonWorker;
    private BufferedWriter workerStdin;
    private BufferedReader workerStdout;

    public FinanceService(JdbcTemplate jdbc, AppSettings settings, SecurityService security) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.security = security;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version VARCHAR(64) PRIMARY KEY,
                applied_at VARCHAR(64) NOT NULL
            )
            """);

        applyMigration("V1__initial_schema", List.of(
            """
            CREATE TABLE IF NOT EXISTS users (
                id VARCHAR(36) PRIMARY KEY,
                email VARCHAR(320) NOT NULL UNIQUE,
                password_hash VARCHAR(255),
                full_name VARCHAR(200),
                avatar_url VARCHAR(500),
                google_sub VARCHAR(128) UNIQUE,
                base_currency VARCHAR(3) NOT NULL,
                timezone VARCHAR(64) NOT NULL,
                locale VARCHAR(16) NOT NULL,
                ledger_merkle_root VARCHAR(64),
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                is_admin BOOLEAN NOT NULL DEFAULT FALSE,
                last_login_at VARCHAR(64),
                created_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS refresh_tokens (
                id VARCHAR(36) PRIMARY KEY,
                user_id VARCHAR(36) NOT NULL,
                token_hash VARCHAR(64) NOT NULL UNIQUE,
                family_id VARCHAR(36) NOT NULL,
                client VARCHAR(16) NOT NULL,
                user_agent VARCHAR(500),
                expires_at VARCHAR(64) NOT NULL,
                revoked_at VARCHAR(64),
                created_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bank_accounts (
                id VARCHAR(36) PRIMARY KEY,
                user_id VARCHAR(36) NOT NULL,
                name VARCHAR(120) NOT NULL,
                institution VARCHAR(120) NOT NULL,
                account_type VARCHAR(32) NOT NULL,
                currency VARCHAR(3) NOT NULL,
                balance_minor BIGINT NOT NULL,
                masked_number VARCHAR(512),
                provider VARCHAR(32) NOT NULL,
                external_id VARCHAR(128),
                last_synced_at VARCHAR(64),
                created_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS transactions (
                id VARCHAR(36) PRIMARY KEY,
                user_id VARCHAR(36) NOT NULL,
                account_id VARCHAR(36) NOT NULL,
                occurred_at VARCHAR(64) NOT NULL,
                direction VARCHAR(16) NOT NULL,
                amount_minor BIGINT NOT NULL,
                currency VARCHAR(3) NOT NULL,
                amount_base_minor BIGINT NOT NULL,
                fx_rate DOUBLE PRECISION NOT NULL,
                raw_description VARCHAR(500) NOT NULL,
                merchant_name VARCHAR(160) NOT NULL,
                category VARCHAR(64) NOT NULL,
                category_source VARCHAR(16) NOT NULL,
                category_confidence DOUBLE PRECISION NOT NULL,
                is_recurring BOOLEAN NOT NULL DEFAULT FALSE,
                is_anomaly BOOLEAN NOT NULL DEFAULT FALSE,
                anomaly_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                anomaly_reason VARCHAR(500),
                external_id VARCHAR(128),
                created_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS category_rules (
                id VARCHAR(36) PRIMARY KEY,
                user_id VARCHAR(36) NOT NULL,
                merchant_pattern VARCHAR(160) NOT NULL,
                category VARCHAR(64) NOT NULL,
                hit_count INT NOT NULL DEFAULT 1,
                created_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS revoked_tokens (
                jti VARCHAR(64) PRIMARY KEY,
                revoked_at VARCHAR(64) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS rate_limit_events (
                id VARCHAR(36) PRIMARY KEY,
                bucket_key VARCHAR(160) NOT NULL,
                ts_ms BIGINT NOT NULL
            )
            """
        ));

        applyMigration("V2__indexes_and_audit_columns", List.of(
            "CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)",
            "CREATE INDEX IF NOT EXISTS idx_accounts_user ON bank_accounts(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_txns_user_occurred ON transactions(user_id, occurred_at)",
            "CREATE INDEX IF NOT EXISTS idx_txns_user_ext ON transactions(user_id, external_id)",
            "CREATE INDEX IF NOT EXISTS idx_rules_user_pattern ON category_rules(user_id, merchant_pattern)",
            "CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family_id)",
            "CREATE INDEX IF NOT EXISTS idx_rate_limit_bucket_ts ON rate_limit_events(bucket_key, ts_ms)"
        ));
    }

    private void applyMigration(String version, List<String> statements) {
        Integer applied = jdbc.queryForObject(
            "SELECT COUNT(*) FROM schema_migrations WHERE version = ?",
            Integer.class,
            version
        );
        if (applied != null && applied > 0) {
            return;
        }
        for (String sql : statements) {
            jdbc.execute(sql);
        }
        jdbc.update(
            "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)",
            version,
            Instant.now().toString()
        );
    }

    @PreDestroy
    public void shutdownWorker() {
        synchronized (workerLock) {
            destroyWorkerQuietly();
        }
    }

    public boolean checkDatabaseReady() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public UserRead authenticateOrRegisterUser(String email, String rawPassword, String fullName) {
        String normEmail = email.trim().toLowerCase();
        String passwordToUse = (rawPassword != null && !rawPassword.isBlank()) ? rawPassword : "default-dev-password";
        if (passwordToUse.length() < 6) {
            throw new AppException(422, "validation_error", "Password must be at least 6 characters");
        }

        List<Map<String, Object>> existingRows = jdbc.queryForList(
            "SELECT * FROM users WHERE email = ?",
            normEmail
        );
        String now = Instant.now().toString();
        if (existingRows.isEmpty()) {
            UUID id = UUID.randomUUID();
            String pwdHash = security.hashPassword(passwordToUse);
            jdbc.update(
                """
                INSERT INTO users (id, email, password_hash, full_name, avatar_url, google_sub, base_currency, timezone, locale, ledger_merkle_root, is_active, is_admin, last_login_at, created_at)
                VALUES (?, ?, ?, ?, NULL, NULL, ?, 'Asia/Kolkata', 'en-IN', NULL, TRUE, FALSE, ?, ?)
                """,
                id.toString(), normEmail, pwdHash,
                (fullName != null && !fullName.isBlank()) ? fullName.trim() : null,
                settings.defaultCurrency, now, now
            );
            return getUserById(id);
        }

        Map<String, Object> row = existingRows.get(0);
        if (!Boolean.TRUE.equals(row.get("is_active"))) {
            throw new AppException(401, "not_authenticated", "User account is inactive");
        }
        UUID userId = UUID.fromString(String.valueOf(row.get("id")));
        String storedHash = row.get("password_hash") != null ? String.valueOf(row.get("password_hash")) : null;

        if (storedHash != null && !storedHash.isBlank()) {
            if (!security.verifyPassword(passwordToUse, storedHash)) {
                throw new AppException(401, "not_authenticated", "Invalid email or password");
            }
        } else {
            jdbc.update(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                security.hashPassword(passwordToUse),
                userId.toString()
            );
        }

        String currentName = row.get("full_name") != null ? String.valueOf(row.get("full_name")) : null;
        String updatedName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : currentName;
        jdbc.update(
            "UPDATE users SET full_name = ?, last_login_at = ? WHERE id = ?",
            updatedName, now, userId.toString()
        );
        return getUserById(userId);
    }

    public UserRead getUserById(UUID userId) {
        List<UserRead> rows = jdbc.query(
            "SELECT * FROM users WHERE id = ? AND is_active = TRUE",
            this::mapUser,
            userId.toString()
        );
        if (rows.isEmpty()) {
            throw new AppException(401, "not_authenticated", "User not found or inactive");
        }
        return rows.get(0);
    }

    @Transactional
    public UserRead updateUser(UUID userId, Map<String, Object> body) {
        UserRead current = getUserById(userId);
        Set<String> allowed = Set.of("full_name", "base_currency", "timezone", "locale");
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new AppException(422, "validation_error", "Extra inputs are not permitted: " + key);
            }
        }

        String fullName = current.fullName();
        if (body.containsKey("full_name")) {
            Object v = body.get("full_name");
            if (v == null) {
                fullName = null;
            } else {
                String s = String.valueOf(v).trim();
                if (s.isEmpty() || s.length() > 200) {
                    throw new AppException(422, "validation_error", "Invalid full_name");
                }
                fullName = s;
            }
        }

        String baseCurrency = current.baseCurrency();
        if (body.get("base_currency") != null) {
            String c = String.valueOf(body.get("base_currency")).toUpperCase();
            if (!ApiModels.isValidCurrency(c)) {
                throw new AppException(422, "validation_error", "Unsupported currency code");
            }
            baseCurrency = c;
        }

        String timezone = current.timezone();
        if (body.get("timezone") != null) {
            String tz = String.valueOf(body.get("timezone"));
            if (!ApiModels.isValidTimezone(tz)) {
                throw new AppException(422, "validation_error", "Unknown IANA timezone");
            }
            timezone = tz;
        }

        String locale = current.locale();
        if (body.get("locale") != null) {
            String loc = String.valueOf(body.get("locale"));
            if (!loc.matches("^[A-Za-z-]{2,16}$")) {
                throw new AppException(422, "validation_error", "Invalid locale");
            }
            locale = loc;
        }

        jdbc.update(
            "UPDATE users SET full_name = ?, base_currency = ?, timezone = ?, locale = ? WHERE id = ?",
            fullName, baseCurrency, timezone, locale, userId.toString()
        );

        if (!baseCurrency.equals(current.baseCurrency())) {
            List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT id, amount_minor, currency FROM transactions WHERE user_id = ?",
                userId.toString()
            );
            for (Map<String, Object> t : txns) {
                String tid = String.valueOf(t.get("id"));
                long amtMinor = ((Number) t.get("amount_minor")).longValue();
                String curr = String.valueOf(t.get("currency"));
                FxConversion conv = ApiModels.convertMinorUnits(amtMinor, curr, baseCurrency);
                jdbc.update(
                    "UPDATE transactions SET amount_base_minor = ?, fx_rate = ? WHERE id = ?",
                    conv.amountBaseMinor(), conv.fxRate(), tid
                );
            }
            recomputeAndStoreLedgerSeal(userId);
        }

        return getUserById(userId);
    }

    @Transactional
    public IssuedTokens issueTokens(UUID userId, String client, String userAgent, UUID familyId) {
        IssuedTokens tokens = security.createTokenStrings(userId);
        UUID fam = familyId != null ? familyId : UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, client, user_agent, expires_at, revoked_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            security.hashToken(tokens.refreshToken()),
            fam.toString(),
            client != null ? client : "web",
            userAgent,
            tokens.refreshExpiresAt().toString(),
            Instant.now().toString()
        );
        return tokens;
    }

    public record RotatedSession(UserRead user, IssuedTokens tokens, String client) {}

    @Transactional
    public RotatedSession rotateRefreshToken(String rawRefreshToken, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AppException(401, "not_authenticated", "Missing refresh token");
        }
        String hash = security.hashToken(rawRefreshToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM refresh_tokens WHERE token_hash = ?",
            hash
        );
        if (rows.isEmpty()) {
            throw new AppException(401, "not_authenticated", "Invalid refresh token");
        }
        Map<String, Object> row = rows.get(0);
        String tokenId = String.valueOf(row.get("id"));
        UUID familyId = UUID.fromString(String.valueOf(row.get("family_id")));
        UUID userId = UUID.fromString(String.valueOf(row.get("user_id")));
        String client = String.valueOf(row.get("client"));
        Object revokedAt = row.get("revoked_at");
        Instant expiresAt = Instant.parse(String.valueOf(row.get("expires_at")));
        String nowStr = Instant.now().toString();

        if (revokedAt != null || security.isFamilyRevoked(familyId)) {
            security.revokeTokenFamily(familyId);
            jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                nowStr, familyId.toString()
            );
            throw new AppException(401, "not_authenticated", "Refresh token has been revoked");
        }

        if (Instant.now().isAfter(expiresAt)) {
            throw new AppException(401, "not_authenticated", "Refresh token has expired");
        }

        // Atomic conditional update to prevent concurrent token reuse races across threads/instances
        int updated = jdbc.update(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
            nowStr, tokenId
        );
        if (updated == 0) {
            security.revokeTokenFamily(familyId);
            jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                nowStr, familyId.toString()
            );
            throw new AppException(401, "not_authenticated", "Refresh token has been revoked");
        }

        UserRead user = getUserById(userId);
        IssuedTokens nextTokens = issueTokens(userId, client, userAgent, familyId);
        return new RotatedSession(user, nextTokens, client);
    }

    @Transactional
    public void revokeRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String hash = security.hashToken(rawRefreshToken);
        jdbc.update(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            Instant.now().toString(), hash
        );
    }

    public List<BankAccountRead> listAccounts(UserRead user) {
        return jdbc.query(
            "SELECT * FROM bank_accounts WHERE user_id = ? ORDER BY created_at ASC",
            (rs, rowNum) -> mapAccount(rs, user.baseCurrency()),
            user.id().toString()
        );
    }

    public Map<String, String> getUserRulesMap(UUID userId) {
        Map<String, String> rules = new LinkedHashMap<>();
        jdbc.query(
            "SELECT merchant_pattern, category FROM category_rules WHERE user_id = ?",
            rs -> {
                rules.put(rs.getString("merchant_pattern"), rs.getString("category"));
            },
            userId.toString()
        );
        return rules;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public List<BankAccountRead> syncMockBankData(UserRead user) {
        Map<String, Object> req = Map.of(
            "base_currency", user.baseCurrency(),
            "user_rules", getUserRulesMap(user.id())
        );
        Map<String, Object> mlOut = invokePythonMlCli("sync-mock", req);
        List<Map<String, Object>> rawAccounts = (List<Map<String, Object>>) mlOut.getOrDefault("accounts", List.of());
        List<Map<String, Object>> rawTxns = (List<Map<String, Object>>) mlOut.getOrDefault("transactions", List.of());

        String now = Instant.now().toString();
        Map<String, UUID> extToAccountId = new HashMap<>();
        for (Map<String, Object> a : rawAccounts) {
            String extId = String.valueOf(a.get("external_id"));
            long bal = ((Number) a.get("balance_minor")).longValue();
            List<String> existingIds = jdbc.queryForList(
                "SELECT id FROM bank_accounts WHERE user_id = ? AND external_id = ?",
                String.class,
                user.id().toString(), extId
            );
            UUID acctId;
            if (existingIds.isEmpty()) {
                acctId = UUID.randomUUID();
                String encMasked = security.encryptField(a.get("masked_number") != null ? String.valueOf(a.get("masked_number")) : null);
                jdbc.update(
                    """
                    INSERT INTO bank_accounts (id, user_id, name, institution, account_type, currency, balance_minor, masked_number, provider, external_id, last_synced_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    acctId.toString(), user.id().toString(),
                    String.valueOf(a.get("name")), String.valueOf(a.get("institution")),
                    String.valueOf(a.get("account_type")), String.valueOf(a.get("currency")),
                    bal, encMasked, String.valueOf(a.get("provider")), extId, now, now
                );
            } else {
                acctId = UUID.fromString(existingIds.get(0));
                jdbc.update(
                    "UPDATE bank_accounts SET balance_minor = ?, last_synced_at = ? WHERE id = ?",
                    bal, now, acctId.toString()
                );
            }
            extToAccountId.put(extId, acctId);
        }

        Set<String> existingExtIds = new HashSet<>(jdbc.queryForList(
            "SELECT external_id FROM transactions WHERE user_id = ? AND external_id IS NOT NULL",
            String.class,
            user.id().toString()
        ));

        for (Map<String, Object> t : rawTxns) {
            String extId = String.valueOf(t.get("external_id"));
            UUID acctId = extToAccountId.get(String.valueOf(t.get("account_external_id")));
            if (acctId == null || existingExtIds.contains(extId)) continue;
            existingExtIds.add(extId);
            insertTransactionRow(user.id(), acctId, t, now);
        }

        recomputeAndStoreLedgerSeal(user.id());
        return listAccounts(user);
    }

    public List<TransactionRead> listTransactions(UserRead user, int limit, int offset, String category, UUID accountId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(user.id().toString());
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (accountId != null) {
            sql.append(" AND account_id = ?");
            args.add(accountId.toString());
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> mapTransaction(rs), args.toArray());
    }

    @Transactional
    public TransactionRead updateTransactionCategory(UserRead user, UUID transactionId, String newCategory, boolean applyToFuture) {
        if (newCategory == null || !ApiModels.VALID_CATEGORIES.contains(newCategory)) {
            throw new AppException(422, "validation_error", "Invalid transaction category");
        }
        List<TransactionRead> rows = jdbc.query(
            "SELECT * FROM transactions WHERE id = ? AND user_id = ?",
            (rs, i) -> mapTransaction(rs),
            transactionId.toString(), user.id().toString()
        );
        if (rows.isEmpty()) {
            throw new AppException(404, "not_found", "Transaction not found");
        }
        TransactionRead txn = rows.get(0);
        jdbc.update(
            "UPDATE transactions SET category = ?, category_source = 'user', category_confidence = 1.0 WHERE id = ?",
            newCategory, transactionId.toString()
        );

        if (applyToFuture && txn.merchantName() != null && !txn.merchantName().isBlank()) {
            List<String> ruleIds = jdbc.queryForList(
                "SELECT id FROM category_rules WHERE user_id = ? AND merchant_pattern = ?",
                String.class,
                user.id().toString(), txn.merchantName()
            );
            if (ruleIds.isEmpty()) {
                jdbc.update(
                    "INSERT INTO category_rules (id, user_id, merchant_pattern, category, hit_count, created_at) VALUES (?, ?, ?, ?, 1, ?)",
                    UUID.randomUUID().toString(), user.id().toString(), txn.merchantName(), newCategory, Instant.now().toString()
                );
            } else {
                jdbc.update(
                    "UPDATE category_rules SET category = ?, hit_count = hit_count + 1 WHERE id = ?",
                    newCategory, ruleIds.get(0)
                );
            }
            jdbc.update(
                "UPDATE transactions SET category = ?, category_source = 'user', category_confidence = 1.0 WHERE user_id = ? AND merchant_name = ?",
                newCategory, user.id().toString(), txn.merchantName()
            );
        }

        recomputeAndStoreLedgerSeal(user.id());
        return jdbc.query(
            "SELECT * FROM transactions WHERE id = ?",
            (rs, i) -> mapTransaction(rs),
            transactionId.toString()
        ).get(0);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public CsvImportResponse importCsvStatement(UserRead user, UUID accountId, String csvContent) {
        if (csvContent == null || csvContent.isBlank() || csvContent.length() > 25_000) {
            throw new AppException(422, "validation_error", "CSV content must be between 1 and 25000 characters");
        }
        String now = Instant.now().toString();
        UUID targetAccountId;
        String acctCurrency;

        if (accountId != null) {
            List<Map<String, Object>> accts = jdbc.queryForList(
                "SELECT id, currency FROM bank_accounts WHERE id = ? AND user_id = ?",
                accountId.toString(), user.id().toString()
            );
            if (accts.isEmpty()) {
                throw new AppException(404, "not_found", "Account not found");
            }
            targetAccountId = accountId;
            acctCurrency = String.valueOf(accts.get(0).get("currency"));
        } else {
            List<Map<String, Object>> defAccts = jdbc.queryForList(
                "SELECT id, currency FROM bank_accounts WHERE user_id = ? AND external_id = 'csv-import-default'",
                user.id().toString()
            );
            if (defAccts.isEmpty()) {
                targetAccountId = UUID.randomUUID();
                acctCurrency = user.baseCurrency();
                jdbc.update(
                    """
                    INSERT INTO bank_accounts (id, user_id, name, institution, account_type, currency, balance_minor, masked_number, provider, external_id, last_synced_at, created_at)
                    VALUES (?, ?, 'CSV Statement Account', 'Statement Import', 'checking', ?, 0, ?, 'csv', 'csv-import-default', ?, ?)
                    """,
                    targetAccountId.toString(), user.id().toString(), acctCurrency, security.encryptField("CSV-IMPORT"), now, now
                );
            } else {
                targetAccountId = UUID.fromString(String.valueOf(defAccts.get(0).get("id")));
                acctCurrency = String.valueOf(defAccts.get(0).get("currency"));
            }
        }

        Map<String, Object> req = Map.of(
            "csv_content", csvContent,
            "account_currency", acctCurrency,
            "base_currency", user.baseCurrency(),
            "user_rules", getUserRulesMap(user.id())
        );
        Map<String, Object> parsed = invokePythonMlCli("parse-csv", req);
        List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.getOrDefault("transactions", List.of());

        Set<String> existingExtIds = new HashSet<>(jdbc.queryForList(
            "SELECT external_id FROM transactions WHERE user_id = ? AND external_id IS NOT NULL",
            String.class,
            user.id().toString()
        ));

        int imported = 0;
        int skipped = 0;
        long netDeltaMinor = 0;
        for (Map<String, Object> item : items) {
            String extId = String.valueOf(item.get("external_id"));
            if (existingExtIds.contains(extId)) {
                skipped++;
                continue;
            }
            existingExtIds.add(extId);
            insertTransactionRow(user.id(), targetAccountId, item, now);
            long itemMinor = ((Number) item.get("amount_minor")).longValue();
            String itemCurr = String.valueOf(item.get("currency"));
            long acctMinor = ApiModels.convertMinorUnits(itemMinor, itemCurr, acctCurrency).amountBaseMinor();
            if ("credit".equalsIgnoreCase(String.valueOf(item.get("direction")))) {
                netDeltaMinor += acctMinor;
            } else {
                netDeltaMinor -= acctMinor;
            }
            imported++;
        }

        if (imported > 0) {
            if (accountId == null) {
                jdbc.update(
                    "UPDATE bank_accounts SET balance_minor = CASE WHEN balance_minor + ? < 0 THEN 0 ELSE balance_minor + ? END, last_synced_at = ? WHERE id = ?",
                    netDeltaMinor, netDeltaMinor, now, targetAccountId.toString()
                );
            }
            annotateUserTransactions(user);
            recomputeAndStoreLedgerSeal(user.id());
        }
        return new CsvImportResponse(imported, skipped);
    }

    @SuppressWarnings("unchecked")
    private void annotateUserTransactions(UserRead user) {
        List<Map<String, Object>> allTxns = getUserTransactionsAsMaps(user.id());
        if (allTxns.isEmpty()) return;
        Map<String, Object> ann = invokePythonMlCli("annotate", Map.of(
            "base_currency", user.baseCurrency(),
            "transactions", allTxns
        ));
        Set<String> recMerchants = new HashSet<>((List<String>) ann.getOrDefault("recurring_merchants", List.of()));
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) ann.getOrDefault("anomalies", List.of());
        Map<String, Map<String, Object>> anomById = new HashMap<>();
        for (Map<String, Object> a : anomalies) {
            anomById.put(String.valueOf(a.get("transaction_id")), a);
        }
        for (Map<String, Object> t : allTxns) {
            String tid = String.valueOf(t.get("id"));
            boolean isRec = "debit".equalsIgnoreCase(String.valueOf(t.get("direction")))
                && recMerchants.contains(String.valueOf(t.get("merchant_name")));
            Map<String, Object> anom = anomById.get(tid);
            boolean isAnom = anom != null;
            double score = isAnom ? ((Number) anom.get("robust_zscore")).doubleValue() : 0.0;
            String reason = isAnom ? String.valueOf(anom.get("reason")) : null;
            jdbc.update(
                "UPDATE transactions SET is_recurring = ?, is_anomaly = ?, anomaly_score = ?, anomaly_reason = ? WHERE id = ?",
                isRec, isAnom, score, reason, tid
            );
        }
    }

    private List<TransactionRecord> toVerifierRecords(List<Map<String, Object>> dbTxns) {
        List<TransactionRecord> records = new ArrayList<>(dbTxns.size());
        for (Map<String, Object> t : dbTxns) {
            TransactionRecord r = new TransactionRecord();
            r.id = String.valueOf(t.get("id"));
            r.occurredAt = String.valueOf(t.get("occurred_at"));
            r.direction = String.valueOf(t.get("direction"));
            r.amountMinor = ((Number) t.get("amount_minor")).longValue();
            r.currency = String.valueOf(t.get("currency"));
            r.amountBaseMinor = ((Number) t.get("amount_base_minor")).longValue();
            r.fxRate = ((Number) t.get("fx_rate")).doubleValue();
            r.category = String.valueOf(t.get("category"));
            records.add(r);
        }
        return records;
    }

    public void recomputeAndStoreLedgerSeal(UUID userId) {
        List<Map<String, Object>> dbTxns = getUserTransactionsAsMaps(userId);
        VerificationResult audit = LedgerSecurityVerifier.verifyLedger(toVerifierRecords(dbTxns));
        jdbc.update(
            "UPDATE users SET ledger_merkle_root = ? WHERE id = ?",
            audit.auditSealSha256,
            userId.toString()
        );
    }

    public Map<String, Object> getUserMlInsights(UserRead user) {
        List<BankAccountRead> accts = listAccounts(user);
        long liquidBalance = accts.stream()
            .filter(a -> Set.of("savings", "checking", "wallet").contains(a.accountType().toLowerCase()))
            .mapToLong(BankAccountRead::balanceBaseMinor)
            .sum();

        List<Map<String, Object>> dbTxns = getUserTransactionsAsMaps(user.id());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("base_currency", user.baseCurrency());
        req.put("liquid_balance_minor", liquidBalance);
        req.put("user_rules", getUserRulesMap(user.id()));
        req.put("transactions", dbTxns);

        Map<String, Object> report = invokePythonMlCli("insights", req);

        VerificationResult audit = LedgerSecurityVerifier.verifyLedger(toVerifierRecords(dbTxns));
        List<String> storedRoots = jdbc.queryForList(
            "SELECT ledger_merkle_root FROM users WHERE id = ?",
            String.class,
            user.id().toString()
        );
        String storedRoot = (!storedRoots.isEmpty()) ? storedRoots.get(0) : null;
        if (storedRoot == null && !dbTxns.isEmpty()) {
            jdbc.update(
                "UPDATE users SET ledger_merkle_root = ? WHERE id = ?",
                audit.auditSealSha256,
                user.id().toString()
            );
        } else if (storedRoot != null && !storedRoot.equals(audit.auditSealSha256)) {
            log.warn("Tamper detection alert for user {}: expected Merkle root {} but computed {}", user.id(), storedRoot, audit.auditSealSha256);
            audit.isValid = false;
        }
        report.put("java_audit", audit.toMap());
        return report;
    }

    private List<Map<String, Object>> getUserTransactionsAsMaps(UUID userId) {
        return jdbc.query(
            "SELECT * FROM transactions WHERE user_id = ? ORDER BY occurred_at ASC",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("occurred_at", rs.getString("occurred_at"));
                m.put("direction", rs.getString("direction"));
                m.put("amount_minor", rs.getLong("amount_minor"));
                m.put("currency", rs.getString("currency"));
                m.put("amount_base_minor", rs.getLong("amount_base_minor"));
                m.put("fx_rate", rs.getDouble("fx_rate"));
                m.put("merchant_name", rs.getString("merchant_name"));
                m.put("category", rs.getString("category"));
                m.put("category_source", rs.getString("category_source"));
                m.put("category_confidence", rs.getDouble("category_confidence"));
                return m;
            },
            userId.toString()
        );
    }

    private void insertTransactionRow(UUID userId, UUID accountId, Map<String, Object> t, String now) {
        boolean isRec = Boolean.TRUE.equals(t.get("is_recurring"));
        boolean isAnom = Boolean.TRUE.equals(t.get("is_anomaly"));
        double anomScore = t.get("anomaly_score") instanceof Number n ? n.doubleValue() : 0.0;
        String anomReason = t.get("anomaly_reason") != null ? String.valueOf(t.get("anomaly_reason")) : null;
        jdbc.update(
            """
            INSERT INTO transactions (
                id, user_id, account_id, occurred_at, direction, amount_minor, currency,
                amount_base_minor, fx_rate, raw_description, merchant_name, category,
                category_source, category_confidence, is_recurring, is_anomaly,
                anomaly_score, anomaly_reason, external_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            accountId.toString(),
            String.valueOf(t.get("occurred_at")),
            String.valueOf(t.get("direction")),
            ((Number) t.get("amount_minor")).longValue(),
            String.valueOf(t.get("currency")),
            ((Number) t.get("amount_base_minor")).longValue(),
            ((Number) t.get("fx_rate")).doubleValue(),
            String.valueOf(t.get("raw_description")),
            String.valueOf(t.get("merchant_name")),
            String.valueOf(t.get("category")),
            String.valueOf(t.get("category_source")),
            ((Number) t.get("category_confidence")).doubleValue(),
            isRec,
            isAnom,
            anomScore,
            anomReason,
            t.get("external_id") != null ? String.valueOf(t.get("external_id")) : null,
            now
        );
    }

    private void ensureWorkerAlive() throws Exception {
        if (pythonWorker != null && pythonWorker.isAlive()) {
            return;
        }
        destroyWorkerQuietly();
        File workDir = new File(".").getAbsoluteFile();
        if (!new File(workDir, "app/ml_cli.py").exists() && new File(workDir, "backend/app/ml_cli.py").exists()) {
            workDir = new File(workDir, "backend");
        }
        File venvPy = new File(workDir, ".venv/bin/python");
        String pythonBin = venvPy.exists() ? venvPy.getAbsolutePath() : "python3";

        ProcessBuilder pb = new ProcessBuilder(pythonBin, "-m", "app.ml_cli", "worker");
        pb.directory(workDir);
        pythonWorker = pb.start();
        workerStdin = new BufferedWriter(new OutputStreamWriter(pythonWorker.getOutputStream(), StandardCharsets.UTF_8));
        workerStdout = new BufferedReader(new InputStreamReader(pythonWorker.getInputStream(), StandardCharsets.UTF_8));

        Process procRef = pythonWorker;
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(procRef.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    log.warn("[python-ml-worker] {}", line);
                }
            } catch (Exception ignored) {
                // Stream closed on process exit
            }
        }, "python-ml-stderr-drainer");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
    }

    private void destroyWorkerQuietly() {
        try {
            if (workerStdin != null) workerStdin.close();
        } catch (Exception ignored) {}
        try {
            if (workerStdout != null) workerStdout.close();
        } catch (Exception ignored) {}
        if (pythonWorker != null) {
            pythonWorker.destroyForcibly();
            pythonWorker = null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> invokePythonMlCli(String command, Map<String, Object> payload) {
        synchronized (workerLock) {
            try {
                ensureWorkerAlive();
                String reqLine = mapper.writeValueAsString(Map.of("cmd", command, "payload", payload));
                workerStdin.write(reqLine);
                workerStdin.write("\n");
                workerStdin.flush();

                String respLine = CompletableFuture.supplyAsync(() -> {
                    try {
                        return workerStdout.readLine();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).get(20, TimeUnit.SECONDS);

                if (respLine == null) {
                    destroyWorkerQuietly();
                    throw new IllegalStateException("Python ML worker closed stdout unexpectedly");
                }
                Map<String, Object> envelope = mapper.readValue(respLine, new TypeReference<>() {});
                if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                    throw new IllegalStateException("Python ML worker error: " + envelope.get("error"));
                }
                return (Map<String, Object>) envelope.get("data");
            } catch (Exception e) {
                destroyWorkerQuietly();
                log.error("Failed to execute Python ML worker command {}: {}", command, e.getMessage());
                throw new IllegalStateException("Failed to execute Python ML engine: " + e.getMessage(), e);
            }
        }
    }

    private UserRead mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRead(
            UUID.fromString(rs.getString("id")),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("avatar_url"),
            rs.getString("base_currency"),
            rs.getString("timezone"),
            rs.getString("locale"),
            rs.getBoolean("is_admin"),
            rs.getString("created_at")
        );
    }

    private BankAccountRead mapAccount(ResultSet rs, String baseCurrency) throws SQLException {
        long balMinor = rs.getLong("balance_minor");
        String curr = rs.getString("currency");
        long balBase = ApiModels.convertMinorUnits(balMinor, curr, baseCurrency).amountBaseMinor();
        return new BankAccountRead(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("institution"),
            rs.getString("account_type"),
            curr,
            balMinor,
            balBase,
            security.decryptField(rs.getString("masked_number")),
            rs.getString("provider"),
            rs.getString("last_synced_at"),
            rs.getString("created_at")
        );
    }

    private TransactionRead mapTransaction(ResultSet rs) throws SQLException {
        return new TransactionRead(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("account_id")),
            rs.getString("occurred_at"),
            rs.getString("direction"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getLong("amount_base_minor"),
            rs.getDouble("fx_rate"),
            rs.getString("merchant_name"),
            rs.getString("category"),
            rs.getString("category_source"),
            rs.getDouble("category_confidence"),
            rs.getBoolean("is_recurring"),
            rs.getBoolean("is_anomaly"),
            rs.getDouble("anomaly_score"),
            rs.getString("anomaly_reason")
        );
    }
}
