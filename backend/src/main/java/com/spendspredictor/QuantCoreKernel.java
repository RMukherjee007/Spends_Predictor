package com.spendspredictor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-Performance Java 21 In-JVM Quantitative Finance & Statistical Kernel.
 *
 * <p>Replaces the external C++20 native shared library with zero-allocation primitive loops
 * compiled by the HotSpot C2 JIT compiler:
 * <ul>
 *   <li>Deterministic SplitMix64 PRNG for 10,000-path x 30-day Monte Carlo cashflow runway</li>
 *   <li>Exact closed-form Bachelier-Wald Brownian motion with drift barrier probability</li>
 *   <li>Closed-form Minimum Trace (MinT) optimal hierarchical forecast reconciliation</li>
 *   <li>Page's Upper CUSUM change-point detector for lifestyle creep</li>
 * </ul>
 */
public final class QuantCoreKernel {

    public static final String ENGINE_NAME = "Java 21 In-JVM QuantCore (HotSpot C2 JIT)";

    private static final Map<String, Double> FX_TO_INR = Map.of(
        "INR", 1.0,
        "USD", 83.50,
        "EUR", 90.20,
        "GBP", 106.40,
        "JPY", 0.55,
        "KWD", 272.00,
        "AED", 22.73,
        "SGD", 62.10,
        "CAD", 61.20,
        "AUD", 55.40
    );

    private QuantCoreKernel() {}

    /** Fast deterministic SplitMix64 pseudo-random generator. */
    public static final class SplitMix64 {
        private long state;

        public SplitMix64(long seed) {
            this.state = seed;
        }

        public long nextLong() {
            long z = (state += 0x9e3779b97f4a7c15L);
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }

        public int nextIndex(int bound) {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }
    }

    public record MonteCarloResult(
        long[] p10ByDay,
        long[] p50ByDay,
        long[] p90ByDay,
        double mcHazardProb,
        double waldExactProb,
        double executionTimeUs,
        String engine,
        int nSimulations
    ) {}

    public record MinTResult(long totalReconciled, long[] reconciledCategories) {}

    /** Abramowitz & Stegun 7.1.26 error function approximation (max error 1.5e-7). */
    public static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
            * t * Math.exp(-ax * ax);
        return sign * y;
    }

    public static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / 1.4142135623730951));
    }

    /**
     * Exact closed-form Bachelier-Wald Brownian motion with drift first-passage barrier probability:
     * P(min_{0 <= t <= T} B_t <= B_safe) where dB_t = mu * dt + sigma * dW_t.
     */
    public static double waldFirstPassageProb(
        double currentBalance,
        double safeThreshold,
        double dailyNetDrift,
        double dailyVolatility,
        double horizonDays
    ) {
        if (currentBalance <= safeThreshold) {
            return 1.0;
        }
        if (horizonDays <= 0.0 || dailyVolatility <= 1e-6) {
            double projected = currentBalance + dailyNetDrift * horizonDays;
            return projected <= safeThreshold ? 1.0 : 0.0;
        }

        double barrierDist = currentBalance - safeThreshold;
        double denom = dailyVolatility * Math.sqrt(horizonDays);
        double z1 = (-barrierDist - dailyNetDrift * horizonDays) / denom;
        double z2 = (-barrierDist + dailyNetDrift * horizonDays) / denom;

        double term1 = normalCdf(z1);
        double expArg = Math.clamp(
            (-2.0 * dailyNetDrift * barrierDist) / (dailyVolatility * dailyVolatility),
            -60.0,
            60.0
        );
        double term2 = Math.exp(expArg) * normalCdf(z2);
        return Math.clamp(term1 + term2, 0.0, 1.0);
    }

    /**
     * High-speed in-JVM Monte Carlo Cashflow Runway Simulator (10,000 paths x 30 days).
     */
    public static MonteCarloResult runMonteCarloRunway(
        long initialBalance,
        long safeThreshold,
        double[] dailyDiscPool,
        double[] netScheduledByDay,
        int daysToPayday,
        int nSims,
        long seed
    ) {
        int horizonDays = netScheduledByDay.length;
        double[] pool = (dailyDiscPool != null && dailyDiscPool.length > 0)
            ? dailyDiscPool
            : new double[] {150_000.0};

        int effectiveSims = Math.max(1, nSims);
        int effectivePayday = Math.max(1, Math.min(horizonDays, daysToPayday));

        double sumSched = 0.0;
        for (int i = 0; i < effectivePayday && i < horizonDays; i++) {
            sumSched += netScheduledByDay[i];
        }
        double avgSchedBeforePayday = horizonDays > 0 ? sumSched / effectivePayday : 0.0;

        double sumPool = 0.0;
        for (double v : pool) sumPool += v;
        double meanDisc = sumPool / pool.length;

        double sumSq = 0.0;
        for (double v : pool) {
            double d = v - meanDisc;
            sumSq += d * d;
        }
        double stdDisc = Math.max(Math.sqrt(sumSq / pool.length), Math.max(0.15 * meanDisc, 100.0));
        double dailyNetDrift = avgSchedBeforePayday - meanDisc;

        long t0 = System.nanoTime();

        SplitMix64 rng = new SplitMix64(seed);
        double[] currentBalances = new double[effectiveSims];
        Arrays.fill(currentBalances, (double) initialBalance);

        boolean[] breached = new boolean[effectiveSims];
        if (initialBalance < safeThreshold) {
            Arrays.fill(breached, true);
        }

        double[] sortedCol = new double[effectiveSims];
        long[] outP10 = new long[horizonDays + 1];
        long[] outP50 = new long[horizonDays + 1];
        long[] outP90 = new long[horizonDays + 1];
        outP10[0] = initialBalance;
        outP50[0] = initialBalance;
        outP90[0] = initialBalance;

        int idx10 = (int) (0.10 * (effectiveSims - 1));
        int idx50 = (int) (0.50 * (effectiveSims - 1));
        int idx90 = (int) (0.90 * (effectiveSims - 1));
        double safeThreshD = (double) safeThreshold;
        int poolSize = pool.length;

        for (int day = 1; day <= horizonDays; day++) {
            double netSched = netScheduledByDay[day - 1];
            boolean checkHazard = (day <= effectivePayday);

            for (int s = 0; s < effectiveSims; s++) {
                double sampledSpend = pool[rng.nextIndex(poolSize)];
                double nextBal = currentBalances[s] + netSched - sampledSpend;
                currentBalances[s] = nextBal;
                sortedCol[s] = nextBal;
                if (checkHazard && nextBal < safeThreshD) {
                    breached[s] = true;
                }
            }

            Arrays.sort(sortedCol);
            outP10[day] = Math.round(sortedCol[idx10]);
            outP50[day] = Math.round(sortedCol[idx50]);
            outP90[day] = Math.round(sortedCol[idx90]);
        }

        int breachCount = 0;
        for (boolean b : breached) {
            if (b) breachCount++;
        }
        double mcProb = (double) breachCount / effectiveSims;
        double waldProb = waldFirstPassageProb(
            (double) initialBalance,
            (double) safeThreshold,
            dailyNetDrift,
            stdDisc,
            (double) effectivePayday
        );
        double elapsedUs = Math.round((System.nanoTime() - t0) / 100.0) / 10.0;

        return new MonteCarloResult(
            outP10,
            outP50,
            outP90,
            mcProb,
            waldProb,
            elapsedUs,
            ENGINE_NAME,
            effectiveSims
        );
    }

    /**
     * Exact closed-form Minimum Trace (MinT) optimal hierarchical forecast reconciliation.
     */
    public static MinTResult mintReconcile(double[] yHat, double[] varB, double yTop, double varTop) {
        if (yHat == null || yHat.length == 0) {
            return new MinTResult(0L, new long[0]);
        }
        int nCats = yHat.length;
        double sumB = 0.0;
        double sumVar = Math.max(1.0, varTop);
        for (int i = 0; i < nCats; i++) {
            sumB += yHat[i];
            sumVar += Math.max(1.0, i < varB.length ? varB[i] : 1.0);
        }
        double discrepancy = yTop - sumB;
        long[] reconciled = new long[nCats];
        long total = 0L;
        for (int i = 0; i < nCats; i++) {
            double v = Math.max(1.0, i < varB.length ? varB[i] : 1.0);
            double weight = v / sumVar;
            long adjusted = Math.max(0L, Math.round(yHat[i] + weight * discrepancy));
            reconciled[i] = adjusted;
            total += adjusted;
        }
        return new MinTResult(total, reconciled);
    }

    /**
     * Page's Upper CUSUM change-point statistic.
     */
    public static double pageCusum(double[] series, double baselineMean, double baselineStd, double allowanceK) {
        if (series == null || series.length == 0) {
            return 0.0;
        }
        double sigma = Math.max(baselineStd, 1.0);
        double sPos = 0.0;
        for (double val : series) {
            double z = (val - baselineMean) / sigma;
            sPos = Math.max(0.0, sPos + z - allowanceK);
        }
        return sPos;
    }

    private static long convertFromInrMinor(long inrMinor, String targetCurrency) {
        String curr = targetCurrency != null ? targetCurrency.toUpperCase() : "INR";
        int targetDec = ApiModels.CURRENCY_MINOR_UNITS.getOrDefault(curr, 2);
        double fxToInr = FX_TO_INR.getOrDefault(curr, 1.0);
        double inrMajor = inrMinor / 100.0;
        double targetMajor = inrMajor / fxToInr;
        return Math.max(1L, Math.round(targetMajor * Math.pow(10, targetDec)));
    }

    private static LocalDate parseDate(Object raw) {
        if (raw == null) return LocalDate.now(ZoneOffset.UTC);
        String s = String.valueOf(raw).trim();
        try {
            if (s.length() >= 10) {
                return LocalDate.parse(s.substring(0, 10));
            }
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (Exception e) {
            return Instant.now().atOffset(ZoneOffset.UTC).toLocalDate();
        }
    }

    /**
     * Computes the full 30-day CashflowRunwaySchema map directly in-JVM for FinanceService.
     */
    public static Map<String, Object> computeCashflowRunway(
        List<Map<String, Object>> dbTxns,
        List<Map<String, Object>> recurringBills,
        long liquidBalanceMinor,
        String currency,
        LocalDate nowDate
    ) {
        if (dbTxns == null || dbTxns.isEmpty()) {
            Map<String, Object> emptyRunway = new LinkedHashMap<>();
            emptyRunway.put("current_liquid_balance_minor", liquidBalanceMinor);
            emptyRunway.put("safe_threshold_minor", 0L);
            emptyRunway.put("overdraft_watch_probability_pct", 0.0);
            emptyRunway.put("wald_analytical_probability_pct", 0.0);
            emptyRunway.put("expected_days_above_threshold", 30);
            emptyRunway.put("next_predicted_payday", nowDate.withDayOfMonth(1).plusMonths(1).toString());
            emptyRunway.put("mc_execution_time_us", 0.0);
            emptyRunway.put("mc_engine", ENGINE_NAME);
            emptyRunway.put("mc_simulations_count", 10000);
            emptyRunway.put("trajectory", List.of());
            return emptyRunway;
        }

        long safeThreshold = convertFromInrMinor(2_500_000L, currency);
        LocalDate nextPayday = nowDate.withDayOfMonth(1).plusMonths(1);
        LocalDate cutoff = nowDate.minusDays(60);

        Map<LocalDate, Long> dailyDisc = new HashMap<>();
        List<Long> creditAmounts = new ArrayList<>();

        for (Map<String, Object> t : dbTxns) {
            String dir = String.valueOf(t.getOrDefault("direction", "debit")).toLowerCase();
            long amtBase = ((Number) t.getOrDefault("amount_base_minor", 0L)).longValue();
            LocalDate d = parseDate(t.get("occurred_at"));
            String cat = String.valueOf(t.getOrDefault("category", "other")).toLowerCase();

            if ("credit".equals(dir)) {
                creditAmounts.add(amtBase);
            } else if (!d.isBefore(cutoff) && !"rent".equals(cat) && !"investments".equals(cat)) {
                dailyDisc.merge(d, amtBase, Long::sum);
            }
        }

        double defaultDisc = (double) convertFromInrMinor(150_000L, currency);
        double[] samplePool;
        List<Double> positiveDays = dailyDisc.values().stream()
            .filter(v -> v > 0)
            .map(Long::doubleValue)
            .toList();
        if (positiveDays.isEmpty()) {
            samplePool = new double[] {defaultDisc};
        } else {
            samplePool = positiveDays.stream().mapToDouble(Double::doubleValue).toArray();
        }

        long monthlySalary;
        if (creditAmounts.isEmpty()) {
            monthlySalary = convertFromInrMinor(14_500_000L, currency);
        } else {
            long[] sortedCredits = creditAmounts.stream().mapToLong(Long::longValue).sorted().toArray();
            monthlySalary = sortedCredits[sortedCredits.length / 2];
        }

        Map<Integer, Long> scheduledByOffset = new HashMap<>();
        if (recurringBills != null) {
            for (Map<String, Object> rb : recurringBills) {
                Object nextExp = rb.get("next_expected_date");
                if (nextExp != null) {
                    LocalDate due = parseDate(nextExp);
                    int off = (int) (due.toEpochDay() - nowDate.toEpochDay());
                    if (off >= 1 && off <= 30) {
                        long amt = ((Number) rb.getOrDefault("latest_amount_minor", 0L)).longValue();
                        scheduledByOffset.merge(off, amt, Long::sum);
                    }
                }
            }
        }

        int horizon = 30;
        int daysToPayday = (int) Math.max(1L, Math.min(horizon, nextPayday.toEpochDay() - nowDate.toEpochDay()));
        double[] netScheduled = new double[horizon];
        for (int day = 1; day <= horizon; day++) {
            LocalDate simDate = nowDate.plusDays(day);
            double inflow = (simDate.getDayOfMonth() == 1) ? (double) monthlySalary : 0.0;
            double fixedOut = (double) scheduledByOffset.getOrDefault(day, 0L);
            netScheduled[day - 1] = inflow - fixedOut;
        }

        MonteCarloResult mcRes = runMonteCarloRunway(
            liquidBalanceMinor,
            safeThreshold,
            samplePool,
            netScheduled,
            daysToPayday,
            10000,
            20261027L
        );

        int aboveDays = 0;
        for (int d = 1; d <= horizon; d++) {
            if (mcRes.p50ByDay()[d] >= safeThreshold) {
                aboveDays++;
            }
        }

        List<Map<String, Object>> trajectory = new ArrayList<>();
        for (int d = 0; d <= horizon; d += 2) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("day_offset", d);
            pt.put("date", nowDate.plusDays(d).toString());
            pt.put("p10_balance_minor", mcRes.p10ByDay()[d]);
            pt.put("p50_balance_minor", mcRes.p50ByDay()[d]);
            pt.put("p90_balance_minor", mcRes.p90ByDay()[d]);
            trajectory.add(pt);
        }

        Map<String, Object> runway = new LinkedHashMap<>();
        runway.put("current_liquid_balance_minor", liquidBalanceMinor);
        runway.put("safe_threshold_minor", safeThreshold);
        runway.put("overdraft_watch_probability_pct", Math.round(mcRes.mcHazardProb() * 1000.0) / 10.0);
        runway.put("wald_analytical_probability_pct", Math.round(mcRes.waldExactProb() * 1000.0) / 10.0);
        runway.put("expected_days_above_threshold", aboveDays);
        runway.put("next_predicted_payday", nextPayday.toString());
        runway.put("mc_execution_time_us", mcRes.executionTimeUs());
        runway.put("mc_engine", mcRes.engine());
        runway.put("mc_simulations_count", mcRes.nSimulations());
        runway.put("trajectory", trajectory);
        return runway;
    }
}
