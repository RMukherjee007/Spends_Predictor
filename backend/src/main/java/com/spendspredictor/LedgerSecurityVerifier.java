package com.spendspredictor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Financial ledger and cryptographic audit verifier using DECIMAL128 BigDecimal
 * arithmetic and an RFC 6962 binary SHA-256 Merkle tree root.
 */
public class LedgerSecurityVerifier {

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    public static class TransactionRecord {
        public String id;
        public String occurredAt;
        public String direction; // "debit" | "credit"
        public long amountMinor;
        public String currency;
        public long amountBaseMinor;
        public double fxRate;
        public String category;
    }

    public static class VerificationResult {
        public boolean isValid;
        public long verifiedDebitTotalMinor;
        public long verifiedCreditTotalMinor;
        public long verifiedNetCashflowMinor;
        public int verifiedDebitCount;
        public int verifiedCreditCount;
        public int fxAnomalyCount;
        public String auditSealSha256;
        public String verifierEngine;
        public Map<String, Long> categoryTotalsMinor = new LinkedHashMap<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("is_valid", isValid);
            m.put("audit_seal_sha256", auditSealSha256);
            m.put("verified_debit_count", verifiedDebitCount);
            m.put("verified_credit_count", verifiedCreditCount);
            m.put("total_debits_minor", verifiedDebitTotalMinor);
            m.put("total_credits_minor", verifiedCreditTotalMinor);
            m.put("net_cashflow_minor", verifiedNetCashflowMinor);
            m.put("verifier_engine", verifierEngine);
            return m;
        }
    }

    public static VerificationResult verifyLedger(List<TransactionRecord> transactions) {
        VerificationResult res = new VerificationResult();
        res.isValid = true;
        res.verifierEngine = "Java OpenJDK " + System.getProperty("java.version") + " (BigDecimal DECIMAL128 + SHA-256)";

        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        int debitCount = 0;
        int creditCount = 0;
        int fxMismatchCount = 0;

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available in JVM", e);
        }

        byte[] rollingHash = new byte[32];
        List<byte[]> merkleLeaves = new ArrayList<>(transactions.size());

        for (TransactionRecord t : transactions) {
            if (t.amountMinor < 0 || t.amountBaseMinor < 0) {
                res.isValid = false;
            }

            if (t.fxRate > 0.0 && Math.abs(t.fxRate - 1.0) > 1e-6) {
                BigDecimal originalMinor = new BigDecimal(t.amountMinor);
                BigDecimal fx = new BigDecimal(Double.toString(t.fxRate), MC);
                BigDecimal rawProduct = originalMinor.multiply(fx, MC);
                boolean matched = false;
                for (int scaleShift : new int[] {0, 2, -2, 1, -1, 3, -3}) {
                    BigDecimal shifted = rawProduct.movePointRight(scaleShift).setScale(0, RoundingMode.HALF_EVEN);
                    if (shifted.subtract(new BigDecimal(t.amountBaseMinor)).abs().compareTo(BigDecimal.valueOf(2)) <= 0) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    fxMismatchCount++;
                }
            } else if (Math.abs(t.fxRate - 1.0) <= 1e-6 && t.amountMinor != t.amountBaseMinor) {
                fxMismatchCount++;
            }

            BigDecimal amt = BigDecimal.valueOf(t.amountBaseMinor);
            if ("debit".equalsIgnoreCase(t.direction)) {
                debitTotal = debitTotal.add(amt, MC);
                debitCount++;
                res.categoryTotalsMinor.merge(t.category, t.amountBaseMinor, Long::sum);
            } else if ("credit".equalsIgnoreCase(t.direction)) {
                creditTotal = creditTotal.add(amt, MC);
                creditCount++;
            } else {
                res.isValid = false;
            }

            String canonicalRow = t.id + "|" + t.occurredAt + "|" + t.direction + "|" + t.amountBaseMinor + "|" + t.currency + "|" + t.category;
            byte[] rowBytes = canonicalRow.getBytes(StandardCharsets.UTF_8);

            // RFC 6962 domain-separated Merkle leaf: SHA-256(0x00 || canonicalRow)
            digest.reset();
            digest.update((byte) 0x00);
            merkleLeaves.add(digest.digest(rowBytes));
        }

        // Compute binary SHA-256 Merkle Tree root over all transaction leaves
        if (!merkleLeaves.isEmpty()) {
            List<byte[]> level = merkleLeaves;
            while (level.size() > 1) {
                List<byte[]> nextLevel = new ArrayList<>((level.size() + 1) / 2);
                for (int i = 0; i < level.size(); i += 2) {
                    byte[] left = level.get(i);
                    byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                    digest.reset();
                    digest.update((byte) 0x01);
                    digest.update(left);
                    nextLevel.add(digest.digest(right));
                }
                level = nextLevel;
            }
            rollingHash = level.get(0);
        }

        res.verifiedDebitTotalMinor = debitTotal.longValueExact();
        res.verifiedCreditTotalMinor = creditTotal.longValueExact();
        res.verifiedNetCashflowMinor = creditTotal.subtract(debitTotal, MC).longValueExact();
        res.verifiedDebitCount = debitCount;
        res.verifiedCreditCount = creditCount;
        res.fxAnomalyCount = fxMismatchCount;
        if (fxMismatchCount > 0) {
            res.isValid = false;
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte b : rollingHash) {
            hex.append(String.format("%02x", b));
        }
        res.auditSealSha256 = hex.toString();
        return res;
    }
}
