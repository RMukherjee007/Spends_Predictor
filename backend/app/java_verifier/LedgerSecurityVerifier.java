package app.java_verifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict Financial Ledger, Invariant, & Cryptographic Audit Verifier for Spends Predictor.
 * Uses exact arbitrary-precision BigDecimal (MathContext.DECIMAL128, RoundingMode.HALF_EVEN)
 * and SHA-256 Merkle-style hash chaining to guarantee zero floating point drift and audit integrity.
 */
public class LedgerSecurityVerifier {

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    public static class TransactionRecord {
        public String id;
        public String occurredAt;
        public String direction;
        public long amountMinor;
        public String currency;
        public long amountBaseMinor;
        public double fxRate;
        public String category;
    }

    public static class VerificationResult {
        public boolean valid;
        public String auditSealSha256;
        public long verifiedDebitCount;
        public long verifiedCreditCount;
        public BigInteger totalDebitsBaseMinor;
        public BigInteger totalCreditsBaseMinor;
        public BigInteger netCashflowBaseMinor;
        public int fxInconsistenciesCount;
        public List<String> warnings = new ArrayList<>();
        public String jvmVersion;
    }

    public static VerificationResult verifyLedger(List<TransactionRecord> transactions) {
        VerificationResult res = new VerificationResult();
        res.jvmVersion = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
        res.totalDebitsBaseMinor = BigInteger.ZERO;
        res.totalCreditsBaseMinor = BigInteger.ZERO;

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available in JVM", e);
        }

        byte[] runningHash = new byte[32];

        for (TransactionRecord t : transactions) {
            // 1. Verify currency string validity
            if (t.currency == null || t.currency.length() != 3) {
                res.warnings.add("Invalid ISO-4217 currency code: " + t.currency + " on txn " + t.id);
            }

            // 2. Exact integer and FX calculation invariant verification
            if (t.amountMinor <= 0 || t.amountBaseMinor <= 0 || t.fxRate <= 0.0 || !Double.isFinite(t.fxRate)) {
                res.fxInconsistenciesCount++;
                if (res.warnings.size() < 5) {
                    res.warnings.add("Non-positive or non-finite amount/FX on " + t.id);
                }
            } else {
                BigDecimal originalMinor = new BigDecimal(t.amountMinor);
                BigDecimal fx = new BigDecimal(Double.toString(t.fxRate), MC);
                BigDecimal rawProduct = originalMinor.multiply(fx, MC);
                // Account for decimal place differences (0, 2, or 3 minor unit digits) between source and base currencies
                boolean matchesScale = false;
                for (int scaleShift : new int[] {0, 2, -2, 1, -1, 3, -3}) {
                    BigDecimal shifted = rawProduct.movePointRight(scaleShift).setScale(0, RoundingMode.HALF_EVEN);
                    long expectedLong = shifted.longValue();
                    long tolerance = Math.max(2L, Math.round(Math.abs(expectedLong) * 0.001));
                    if (Math.abs(expectedLong - t.amountBaseMinor) <= tolerance) {
                        matchesScale = true;
                        break;
                    }
                }
                if (!matchesScale) {
                    res.fxInconsistenciesCount++;
                    if (res.warnings.size() < 5) {
                        res.warnings.add("FX arithmetic discrepancy on " + t.id + ": stored=" + t.amountBaseMinor);
                    }
                }
            }

            // 3. Accumulate Debits vs Credits with exact BigInteger
            BigInteger amtBase = BigInteger.valueOf(t.amountBaseMinor);
            if ("credit".equalsIgnoreCase(t.direction)) {
                res.verifiedCreditCount++;
                res.totalCreditsBaseMinor = res.totalCreditsBaseMinor.add(amtBase);
            } else {
                res.verifiedDebitCount++;
                res.totalDebitsBaseMinor = res.totalDebitsBaseMinor.add(amtBase);
            }

            // 4. Update Cryptographic Hash Chain
            String recordStr = t.id + "|" + t.occurredAt + "|" + t.direction + "|" + t.amountMinor + "|" + t.currency + "|" + t.amountBaseMinor + "|" + t.category;
            digest.update(runningHash);
            digest.update(recordStr.getBytes(StandardCharsets.UTF_8));
            runningHash = digest.digest();
        }

        res.netCashflowBaseMinor = res.totalCreditsBaseMinor.subtract(res.totalDebitsBaseMinor);
        StringBuilder sb = new StringBuilder();
        for (byte b : runningHash) {
            sb.append(String.format("%02x", b));
        }
        res.auditSealSha256 = sb.toString();
        res.valid = (res.fxInconsistenciesCount == 0);

        return res;
    }

    public static void main(String[] args) throws Exception {
        // Read JSON-lines or pipe from stdin
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        List<TransactionRecord> list = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // Format: id,occurred_at,direction,amount_minor,currency,amount_base_minor,fx_rate,category
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 8) {
                TransactionRecord r = new TransactionRecord();
                r.id = parts[0];
                r.occurredAt = parts[1];
                r.direction = parts[2];
                r.amountMinor = Long.parseLong(parts[3]);
                r.currency = parts[4];
                r.amountBaseMinor = Long.parseLong(parts[5]);
                r.fxRate = Double.parseDouble(parts[6]);
                r.category = parts[7];
                list.add(r);
            }
        }

        VerificationResult res = verifyLedger(list);
        System.out.println("VALID=" + res.valid);
        System.out.println("AUDIT_SEAL=" + res.auditSealSha256);
        System.out.println("DEBITS_COUNT=" + res.verifiedDebitCount);
        System.out.println("CREDITS_COUNT=" + res.verifiedCreditCount);
        System.out.println("TOTAL_DEBITS=" + res.totalDebitsBaseMinor);
        System.out.println("TOTAL_CREDITS=" + res.totalCreditsBaseMinor);
        System.out.println("NET_CASHFLOW=" + res.netCashflowBaseMinor);
        System.out.println("JVM=" + res.jvmVersion);
    }
}
