package com.spendspredictor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public static class AppException extends RuntimeException {
        public final int statusCode;
        public final String code;
        public final List<Map<String, Object>> details;

        public AppException(int statusCode, String code, String message) {
            this(statusCode, code, message, null);
        }

        public AppException(int statusCode, String code, String message, List<Map<String, Object>> details) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
            this.details = details;
        }
    }

    public static final Set<String> VALID_CATEGORIES = Set.of(
        "dining", "groceries", "rent", "utilities", "transport", "shopping",
        "subscriptions", "healthcare", "travel", "entertainment", "investments",
        "salary", "other"
    );

    public static final Map<String, Integer> CURRENCY_MINOR_UNITS = Map.ofEntries(
        Map.entry("AED", 2), Map.entry("AFN", 2), Map.entry("ALL", 2), Map.entry("AMD", 2), Map.entry("ANG", 2),
        Map.entry("AOA", 2), Map.entry("ARS", 2), Map.entry("AUD", 2), Map.entry("AWG", 2), Map.entry("AZN", 2),
        Map.entry("BAM", 2), Map.entry("BBD", 2), Map.entry("BDT", 2), Map.entry("BGN", 2), Map.entry("BHD", 3),
        Map.entry("BIF", 0), Map.entry("BMD", 2), Map.entry("BND", 2), Map.entry("BOB", 2), Map.entry("BRL", 2),
        Map.entry("BSD", 2), Map.entry("BTN", 2), Map.entry("BWP", 2), Map.entry("BYN", 2), Map.entry("BZD", 2),
        Map.entry("CAD", 2), Map.entry("CDF", 2), Map.entry("CHF", 2), Map.entry("CLP", 0), Map.entry("CNY", 2),
        Map.entry("COP", 2), Map.entry("CRC", 2), Map.entry("CUP", 2), Map.entry("CVE", 2), Map.entry("CZK", 2),
        Map.entry("DJF", 0), Map.entry("DKK", 2), Map.entry("DOP", 2), Map.entry("DZD", 2), Map.entry("EGP", 2),
        Map.entry("ERN", 2), Map.entry("ETB", 2), Map.entry("EUR", 2), Map.entry("FJD", 2), Map.entry("FKP", 2),
        Map.entry("GBP", 2), Map.entry("GEL", 2), Map.entry("GHS", 2), Map.entry("GIP", 2), Map.entry("GMD", 2),
        Map.entry("GNF", 0), Map.entry("GTQ", 2), Map.entry("GYD", 2), Map.entry("HKD", 2), Map.entry("HNL", 2),
        Map.entry("HTG", 2), Map.entry("HUF", 2), Map.entry("IDR", 2), Map.entry("ILS", 2), Map.entry("INR", 2),
        Map.entry("IQD", 3), Map.entry("IRR", 2), Map.entry("ISK", 0), Map.entry("JMD", 2), Map.entry("JOD", 3),
        Map.entry("JPY", 0), Map.entry("KES", 2), Map.entry("KGS", 2), Map.entry("KHR", 2), Map.entry("KMF", 0),
        Map.entry("KPW", 2), Map.entry("KRW", 0), Map.entry("KWD", 3), Map.entry("KYD", 2), Map.entry("KZT", 2),
        Map.entry("LAK", 2), Map.entry("LBP", 2), Map.entry("LKR", 2), Map.entry("LRD", 2), Map.entry("LSL", 2),
        Map.entry("LYD", 3), Map.entry("MAD", 2), Map.entry("MDL", 2), Map.entry("MGA", 2), Map.entry("MKD", 2),
        Map.entry("MMK", 2), Map.entry("MNT", 2), Map.entry("MOP", 2), Map.entry("MRU", 2), Map.entry("MUR", 2),
        Map.entry("MVR", 2), Map.entry("MWK", 2), Map.entry("MXN", 2), Map.entry("MYR", 2), Map.entry("MZN", 2),
        Map.entry("NAD", 2), Map.entry("NGN", 2), Map.entry("NIO", 2), Map.entry("NOK", 2), Map.entry("NPR", 2),
        Map.entry("NZD", 2), Map.entry("OMR", 3), Map.entry("PAB", 2), Map.entry("PEN", 2), Map.entry("PGK", 2),
        Map.entry("PHP", 2), Map.entry("PKR", 2), Map.entry("PLN", 2), Map.entry("PYG", 0), Map.entry("QAR", 2),
        Map.entry("RON", 2), Map.entry("RSD", 2), Map.entry("RUB", 2), Map.entry("RWF", 0), Map.entry("SAR", 2),
        Map.entry("SBD", 2), Map.entry("SCR", 2), Map.entry("SDG", 2), Map.entry("SEK", 2), Map.entry("SGD", 2),
        Map.entry("SHP", 2), Map.entry("SLE", 2), Map.entry("SOS", 2), Map.entry("SRD", 2), Map.entry("SSP", 2),
        Map.entry("STN", 2), Map.entry("SVC", 2), Map.entry("SYP", 2), Map.entry("SZL", 2), Map.entry("THB", 2),
        Map.entry("TJS", 2), Map.entry("TMT", 2), Map.entry("TND", 3), Map.entry("TOP", 2), Map.entry("TRY", 2),
        Map.entry("TTD", 2), Map.entry("TWD", 2), Map.entry("TZS", 2), Map.entry("UAH", 2), Map.entry("UGX", 0),
        Map.entry("USD", 2), Map.entry("UYU", 2), Map.entry("UZS", 2), Map.entry("VES", 2), Map.entry("VND", 0),
        Map.entry("VUV", 0), Map.entry("WST", 2), Map.entry("XAF", 0), Map.entry("XCD", 2), Map.entry("XOF", 0),
        Map.entry("XPF", 0), Map.entry("YER", 2), Map.entry("ZAR", 2), Map.entry("ZMW", 2), Map.entry("ZWG", 2)
    );

    public static final Map<String, Double> USD_RATES = Map.ofEntries(
        Map.entry("USD", 1.0), Map.entry("INR", 84.0), Map.entry("EUR", 0.92), Map.entry("GBP", 0.79),
        Map.entry("AED", 3.67), Map.entry("SGD", 1.34), Map.entry("JPY", 152.0), Map.entry("CAD", 1.38),
        Map.entry("AUD", 1.52), Map.entry("CHF", 0.88), Map.entry("KWD", 0.307), Map.entry("CNY", 7.24),
        Map.entry("HKD", 7.78), Map.entry("NZD", 1.66), Map.entry("SEK", 10.6), Map.entry("NOK", 10.9),
        Map.entry("DKK", 6.86), Map.entry("PLN", 4.01), Map.entry("CZK", 23.3), Map.entry("HUF", 368.0),
        Map.entry("ILS", 3.74), Map.entry("ZAR", 17.7), Map.entry("BRL", 5.70), Map.entry("MXN", 19.9),
        Map.entry("KRW", 1380.0), Map.entry("TWD", 32.1), Map.entry("THB", 33.8), Map.entry("MYR", 4.38),
        Map.entry("IDR", 15700.0), Map.entry("PHP", 58.1), Map.entry("VND", 25300.0), Map.entry("SAR", 3.75),
        Map.entry("QAR", 3.64), Map.entry("BHD", 0.376), Map.entry("OMR", 0.385), Map.entry("JOD", 0.709),
        Map.entry("TRY", 34.2), Map.entry("RUB", 96.5), Map.entry("BDT", 119.5), Map.entry("PKR", 278.0),
        Map.entry("LKR", 293.0), Map.entry("NPR", 134.4), Map.entry("KES", 129.0), Map.entry("NGN", 1650.0),
        Map.entry("EGP", 48.7), Map.entry("CLP", 950.0), Map.entry("COP", 4300.0), Map.entry("PEN", 3.76),
        Map.entry("ARS", 985.0)
    );

    public static boolean isValidCurrency(String code) {
        return code != null && CURRENCY_MINOR_UNITS.containsKey(code.toUpperCase());
    }

    public static boolean isValidTimezone(String tz) {
        return tz != null && ZoneId.getAvailableZoneIds().contains(tz);
    }

    public record FxConversion(long amountBaseMinor, double fxRate) {}

    public static FxConversion convertMinorUnits(long amountMinor, String fromCurrency, String toCurrency) {
        String from = fromCurrency.toUpperCase();
        String to = toCurrency.toUpperCase();
        if (from.equals(to)) {
            return new FxConversion(amountMinor, 1.0);
        }
        int fromDigits = CURRENCY_MINOR_UNITS.getOrDefault(from, 2);
        int toDigits = CURRENCY_MINOR_UNITS.getOrDefault(to, 2);
        double fromPerUsd = USD_RATES.getOrDefault(from, 1.0);
        double toPerUsd = USD_RATES.getOrDefault(to, 1.0);
        BigDecimal rateBd = BigDecimal.valueOf(toPerUsd)
            .divide(BigDecimal.valueOf(fromPerUsd), 10, RoundingMode.HALF_EVEN);
        double rate = rateBd.doubleValue();
        BigDecimal converted = BigDecimal.valueOf(amountMinor)
            .multiply(BigDecimal.valueOf(rate), MathContext.DECIMAL128)
            .movePointRight(toDigits - fromDigits)
            .setScale(0, RoundingMode.HALF_EVEN);
        return new FxConversion(converted.longValueExact(), rate);
    }

    public record CurrencyInfo(String code, @JsonProperty("minor_units") int minorUnits) {}

    public record UserRead(
        UUID id,
        String email,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("base_currency") String baseCurrency,
        String timezone,
        String locale,
        @JsonProperty("is_admin") boolean isAdmin,
        @JsonProperty("created_at") String createdAt
    ) {}

    public record TokenPair(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn
    ) {}

    public record AuthResponse(UserRead user, TokenPair tokens) {}

    public record LoginRequest(
        String email,
        String password,
        @JsonProperty("full_name") String fullName,
        String client
    ) {}

    public record RefreshRequest(@JsonProperty("refresh_token") String refreshToken) {}

    public record BankAccountRead(
        UUID id,
        String name,
        String institution,
        @JsonProperty("account_type") String accountType,
        String currency,
        @JsonProperty("balance_minor") long balanceMinor,
        @JsonProperty("balance_base_minor") long balanceBaseMinor,
        @JsonProperty("masked_number") String maskedNumber,
        String provider,
        @JsonProperty("last_synced_at") String lastSyncedAt,
        @JsonProperty("created_at") String createdAt
    ) {}

    public record TransactionRead(
        UUID id,
        @JsonProperty("account_id") UUID accountId,
        @JsonProperty("occurred_at") String occurredAt,
        String direction,
        @JsonProperty("amount_minor") long amountMinor,
        String currency,
        @JsonProperty("amount_base_minor") long amountBaseMinor,
        @JsonProperty("fx_rate") double fxRate,
        @JsonProperty("merchant_name") String merchantName,
        String category,
        @JsonProperty("category_source") String categorySource,
        @JsonProperty("category_confidence") double categoryConfidence,
        @JsonProperty("is_recurring") boolean isRecurring,
        @JsonProperty("is_anomaly") boolean isAnomaly,
        @JsonProperty("anomaly_score") double anomalyScore,
        @JsonProperty("anomaly_reason") String anomalyReason
    ) {}

    public record TransactionUpdateCategory(
        String category,
        @JsonProperty("apply_to_future") Boolean applyToFuture
    ) {}

    public record CsvImportRequest(
        @JsonProperty("account_id") UUID accountId,
        @JsonProperty("csv_content") String csvContent
    ) {}

    public record CsvImportResponse(
        @JsonProperty("imported_count") int importedCount,
        @JsonProperty("skipped_count") int skippedCount
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
        String code,
        String message,
        @JsonProperty("request_id") String requestId,
        List<Map<String, Object>> details
    ) {}

    public record ErrorEnvelope(ErrorDetail error) {}
}
