package com.spendspredictor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spendspredictor.ApiModels.AppException;
import com.spendspredictor.SpendsPredictorApplication.AppSettings;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityService {
    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    public static final String ACCESS_COOKIE = "sp_access";
    public static final String REFRESH_COOKIE = "sp_refresh";
    public static final String CSRF_COOKIE = "sp_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final byte[] FIELD_KDF_SALT = "spends-predictor-field-gcm-v2".getBytes(StandardCharsets.UTF_8);

    private final AppSettings settings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Set<String> revokedJtis = ConcurrentHashMap.newKeySet();
    private final Set<UUID> revokedFamilies = ConcurrentHashMap.newKeySet();
    private final Map<String, Deque<Double>> rateBuckets = new ConcurrentHashMap<>();
    private final Map<String, Object> bucketLocks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> derivedFieldKeyCache = new ConcurrentHashMap<>();

    public SecurityService(AppSettings settings, JdbcTemplate jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    public record AccessTokenClaims(UUID userId, String jti, Instant expiresAt) {}
    public record IssuedTokens(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {
        public int accessExpiresIn() {
            return (int) Math.max(0L, Duration.between(Instant.now(), accessExpiresAt).getSeconds());
        }
    }

    public String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new AppException(422, "validation_error", "Password must be at least 6 characters");
        }
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || !storedHash.startsWith("pbkdf2$")) {
            return false;
        }
        try {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] salt = dec.decode(parts[2]);
            byte[] expected = dec.decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 key derivation failed", e);
        }
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (settings.trustProxyHeaders) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String firstHop = xff.split(",")[0].trim();
                if (!firstHop.isEmpty() && firstHop.length() <= 64) {
                    return firstHop;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return (remote != null && !remote.isBlank()) ? remote : "local";
    }

    public IssuedTokens createTokenStrings(UUID userId) {
        Instant now = Instant.now();
        Instant accessExp = now.plus(Duration.ofMinutes(settings.accessTokenTtlMinutes));
        Instant refreshExp = now.plus(Duration.ofDays(settings.refreshTokenTtlDays));
        String jti = UUID.randomUUID().toString().replace("-", "");

        try {
            String headerJson = mapper.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", userId.toString());
            payload.put("iss", settings.jwtIssuer);
            payload.put("type", "access");
            payload.put("jti", jti);
            payload.put("iat", now.getEpochSecond());
            payload.put("exp", accessExp.getEpochSecond());
            String payloadJson = mapper.writeValueAsString(payload);

            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String signingInput = b64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String sig = b64.encodeToString(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), settings.jwtSecret.getBytes(StandardCharsets.UTF_8)));
            String jwt = signingInput + "." + sig;

            return new IssuedTokens(jwt, accessExp, generateRandomToken(48), refreshExp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    public AccessTokenClaims decodeAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(401, "not_authenticated", "Invalid or expired access token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new AppException(401, "not_authenticated", "Invalid or expired access token");
        }
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] headerBytes = dec.decode(parts[0]);
            Map<String, Object> header = mapper.readValue(headerBytes, new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg"))) {
                throw new AppException(401, "not_authenticated", "Invalid token algorithm");
            }

            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), settings.jwtSecret.getBytes(StandardCharsets.UTF_8));
            byte[] actualSig = dec.decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                throw new AppException(401, "not_authenticated", "Invalid token signature");
            }

            Map<String, Object> claims = mapper.readValue(dec.decode(parts[1]), new TypeReference<>() {});
            if (!settings.jwtIssuer.equals(claims.get("iss")) || !"access".equals(claims.get("type"))) {
                throw new AppException(401, "not_authenticated", "Invalid token claims");
            }
            long exp = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new AppException(401, "not_authenticated", "Access token has expired");
            }
            String jti = String.valueOf(claims.get("jti"));
            if (isJtiRevoked(jti)) {
                throw new AppException(401, "not_authenticated", "Access token has been revoked");
            }
            UUID sub = UUID.fromString(String.valueOf(claims.get("sub")));
            return new AccessTokenClaims(sub, jti, Instant.ofEpochSecond(exp));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(401, "not_authenticated", "Invalid or expired access token");
        }
    }

    public boolean isJtiRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        if (revokedJtis.contains(jti)) return true;
        try {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM revoked_tokens WHERE jti = ?",
                Integer.class,
                jti
            );
            if (cnt != null && cnt > 0) {
                revokedJtis.add(jti);
                return true;
            }
        } catch (Exception ignored) {
            // Fallback to in-memory set if table is initializing
        }
        return false;
    }

    public void revokeAccessJti(String jti) {
        if (jti != null && !jti.isBlank()) {
            revokedJtis.add(jti);
            try {
                jdbc.update(
                    "INSERT INTO revoked_tokens (jti, revoked_at) VALUES (?, ?)",
                    jti, Instant.now().toString()
                );
            } catch (Exception ignored) {
                // Already recorded in DB
            }
        }
    }

    public void revokeTokenFamily(UUID familyId) {
        if (familyId != null) {
            revokedFamilies.add(familyId);
        }
    }

    public boolean isFamilyRevoked(UUID familyId) {
        return familyId != null && revokedFamilies.contains(familyId);
    }

    public void clearStateForTests() {
        revokedJtis.clear();
        revokedFamilies.clear();
        rateBuckets.clear();
        try {
            jdbc.update("DELETE FROM revoked_tokens");
            jdbc.update("DELETE FROM rate_limit_events");
        } catch (Exception ignored) {
            // Table may not be initialized before first query
        }
    }

    public void checkRateLimit(String bucket, String key, int limit, double windowSeconds) {
        long nowMs = System.currentTimeMillis();
        long cutoffMs = nowMs - Math.round(windowSeconds * 1000.0);
        String bucketKey = bucket + ":" + key;
        Object lock = bucketLocks.computeIfAbsent(bucketKey, k -> new Object());

        synchronized (lock) {
            try {
                jdbc.update("DELETE FROM rate_limit_events WHERE ts_ms < ?", cutoffMs);
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rate_limit_events WHERE bucket_key = ? AND ts_ms >= ?",
                    Integer.class,
                    bucketKey,
                    cutoffMs
                );
                if (count != null && count >= limit) {
                    throw new AppException(429, "rate_limit_exceeded", "Too many requests; please slow down");
                }
                jdbc.update(
                    "INSERT INTO rate_limit_events (id, bucket_key, ts_ms) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), bucketKey, nowMs
                );
                return;
            } catch (AppException e) {
                throw e;
            } catch (Exception ignored) {
                // Fallback to in-memory sliding window if DB unavailable
            }

            double now = nowMs / 1000.0;
            Deque<Double> q = rateBuckets.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
            double cutoff = now - windowSeconds;
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                throw new AppException(429, "rate_limit_exceeded", "Too many requests; please slow down");
            }
            q.addLast(now);
        }
    }

    public String generateRandomToken(int numBytes) {
        byte[] bytes = new byte[numBytes];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean tokensMatch(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    public void enforceCsrf(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase())) {
            return;
        }
        String cookieToken = getCookieValue(request, CSRF_COOKIE);
        String headerToken = request.getHeader(CSRF_HEADER);
        if (!tokensMatch(cookieToken, headerToken)) {
            throw new AppException(403, "csrf_failed", "CSRF token missing or invalid");
        }
    }

    private void addCookie(HttpServletResponse response, String name, String value, boolean httpOnly, String path, long maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(settings.cookieSecure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void setAuthCookies(HttpServletResponse response, IssuedTokens tokens) {
        long refreshMaxAge = (long) settings.refreshTokenTtlDays * 86400L;
        addCookie(response, ACCESS_COOKIE, tokens.accessToken(), true, "/", tokens.accessExpiresIn());
        addCookie(response, REFRESH_COOKIE, tokens.refreshToken(), true, "/api/v1/auth", refreshMaxAge);
        addCookie(response, CSRF_COOKIE, generateRandomToken(32), false, "/", refreshMaxAge);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE, "", true, "/", 0);
        addCookie(response, REFRESH_COOKIE, "", true, "/api/v1/auth", 0);
        addCookie(response, CSRF_COOKIE, "", false, "/", 0);
    }

    public UUID authenticateRequest(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            String token = authHeader.substring(7).trim();
            return decodeAccessToken(token).userId();
        }
        String cookieToken = getCookieValue(request, ACCESS_COOKIE);
        if (cookieToken != null && !cookieToken.isBlank()) {
            enforceCsrf(request);
            return decodeAccessToken(cookieToken).userId();
        }
        throw new AppException(401, "not_authenticated", "Not authenticated");
    }

    // PBKDF2-HMAC-SHA256 + AES-256-GCM authenticated field encryption at rest
    public String encryptField(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] aesKey = deriveAes256Key(primarySecretKey());
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "enc2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(concat(iv, ct));
        } catch (Exception e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decryptField(String stored) {
        if (stored == null) return null;
        List<String> keys = settings.getFieldEncryptionKeys();
        List<String> candidates = keys.isEmpty() ? List.of(settings.jwtSecret) : keys;

        if (stored.startsWith("enc2:")) {
            try {
                byte[] raw = Base64.getUrlDecoder().decode(stored.substring(5));
                byte[] iv = Arrays.copyOfRange(raw, 0, 12);
                byte[] ct = Arrays.copyOfRange(raw, 12, raw.length);
                for (String candidate : candidates) {
                    try {
                        byte[] aesKey = deriveAes256Key(candidate);
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
                        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        // Try next candidate key
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to decode enc2 field ciphertext: {}", e.getMessage());
            }
            log.warn("Unable to decrypt enc2 field with configured keys; returning masked placeholder");
            return "••••";
        }

        if (stored.startsWith("enc1:")) {
            try {
                byte[] raw = Base64.getUrlDecoder().decode(stored.substring(5));
                byte[] iv = Arrays.copyOfRange(raw, 0, 16);
                byte[] ct = Arrays.copyOfRange(raw, 16, raw.length - 32);
                byte[] mac = Arrays.copyOfRange(raw, raw.length - 32, raw.length);
                for (String candidate : candidates) {
                    byte[] keyMaterial = MessageDigest.getInstance("SHA-256").digest(candidate.getBytes(StandardCharsets.UTF_8));
                    byte[] signingKey = Arrays.copyOfRange(keyMaterial, 0, 16);
                    byte[] encKey = Arrays.copyOfRange(keyMaterial, 16, 32);
                    byte[] expectedMac = hmacSha256(concat(iv, ct), signingKey);
                    if (MessageDigest.isEqual(expectedMac, mac)) {
                        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
                        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to decode legacy enc1 field ciphertext: {}", e.getMessage());
            }
            log.warn("Unable to decrypt legacy enc1 field with configured keys; returning masked placeholder");
            return "••••";
        }

        return stored;
    }

    private String primarySecretKey() {
        List<String> keys = settings.getFieldEncryptionKeys();
        return keys.isEmpty() ? settings.jwtSecret : keys.get(0);
    }

    private byte[] deriveAes256Key(String secret) {
        return derivedFieldKeyCache.computeIfAbsent(
            secret,
            s -> pbkdf2(s.toCharArray(), FIELD_KDF_SALT, PBKDF2_ITERATIONS, 256)
        );
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    public String signLedgerSeal(UUID userId, String merkleRoot) {
        if (merkleRoot == null) return null;
        try {
            String key = settings.getEffectiveLedgerSigningKey();
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest((key + ":ledger-signer-domain-v2").getBytes(StandardCharsets.UTF_8));
            byte[] payload = (userId.toString() + ":" + merkleRoot).getBytes(StandardCharsets.UTF_8);
            byte[] sig = hmacSha256(payload, keyBytes);
            StringBuilder sb = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cryptographic ledger seal signature", e);
        }
    }

    public boolean verifyLedgerSeal(UUID userId, String merkleRoot, String sealSignature) {
        if (merkleRoot == null || sealSignature == null || sealSignature.isBlank()) {
            return false;
        }
        String expected = signLedgerSeal(userId, merkleRoot);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            sealSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}
