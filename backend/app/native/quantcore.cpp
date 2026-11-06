// High-Performance C++20 Quantitative Finance & Statistical Kernel for Spends Predictor.
// Compiled with: clang++ -std=c++20 -O3 -shared -fPIC quantcore.cpp -o libquantcore.dylib

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

// SplitMix64 fast deterministic PRNG for reproducible high-speed Monte Carlo simulation
struct SplitMix64 {
    uint64_t state;
    explicit SplitMix64(uint64_t seed) : state(seed) {}

    inline uint64_t next() noexcept {
        uint64_t z = (state += 0x9e3779b97f4a7c15ULL);
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
        return z ^ (z >> 31);
    }

    inline size_t next_index(size_t n) noexcept {
        return static_cast<size_t>(next() % n);
    }
};

inline double normal_cdf(double x) noexcept {
    return 0.5 * (1.0 + std::erf(x / 1.4142135623730951));
}

}  // namespace

extern "C" {

// 1. Exact Closed-Form Bachelier-Wald Brownian Motion with Drift First-Passage Barrier Probability:
//    P(min_{0 <= t <= T} B_t <= B_safe) where dB_t = mu * dt + sigma * dW_t, B_0 > B_safe.
//    Note: mu is net daily balance change (inflow - outflow), so usually mu < 0 between paydays.
double cpp_wald_first_passage_prob(
    double current_balance,
    double safe_threshold,
    double daily_net_drift,
    double daily_volatility,
    double horizon_days
) {
    if (current_balance <= safe_threshold) {
        return 1.0;
    }
    if (horizon_days <= 0.0 || daily_volatility <= 1e-6) {
        double projected = current_balance + daily_net_drift * horizon_days;
        return projected <= safe_threshold ? 1.0 : 0.0;
    }

    const double barrier_dist = current_balance - safe_threshold;
    const double denom = daily_volatility * std::sqrt(horizon_days);
    const double z1 = (-barrier_dist - daily_net_drift * horizon_days) / denom;
    const double z2 = (-barrier_dist + daily_net_drift * horizon_days) / denom;

    const double term1 = normal_cdf(z1);
    // Exponent for reflection term: -2 * mu * b / sigma^2
    const double exp_arg = std::clamp(
        (-2.0 * daily_net_drift * barrier_dist) / (daily_volatility * daily_volatility),
        -60.0,
        60.0
    );
    const double term2 = std::exp(exp_arg) * normal_cdf(z2);
    return std::clamp(term1 + term2, 0.0, 1.0);
}

// 2. High-Speed Native C++20 Monte Carlo Cashflow Runway Simulator (e.g. 10,000 paths x 30 days)
//    Writes p10, p50, p90 balance arrays (length = horizon_days + 1) and returns hazard probability [0..1].
double cpp_monte_carlo_runway(
    int64_t initial_balance,
    int64_t safe_threshold,
    const double* daily_disc_pool,
    int32_t pool_size,
    const double* net_scheduled_by_day,  // length = horizon_days (inflow - scheduled_bills for days 1..H)
    int32_t horizon_days,
    int32_t days_to_payday,
    int32_t n_sims,
    uint64_t seed,
    int64_t* out_p10,
    int64_t* out_p50,
    int64_t* out_p90
) {
    if (n_sims <= 0 || horizon_days <= 0 || pool_size <= 0) {
        return 0.0;
    }

    SplitMix64 rng(seed);
    std::vector<double> current_balances(static_cast<size_t>(n_sims), static_cast<double>(initial_balance));
    std::vector<uint8_t> breached(static_cast<size_t>(n_sims), initial_balance < safe_threshold ? 1 : 0);
    std::vector<double> sorted_col(static_cast<size_t>(n_sims));

    out_p10[0] = initial_balance;
    out_p50[0] = initial_balance;
    out_p90[0] = initial_balance;

    const size_t idx10 = static_cast<size_t>(0.10 * (n_sims - 1));
    const size_t idx50 = static_cast<size_t>(0.50 * (n_sims - 1));
    const size_t idx90 = static_cast<size_t>(0.90 * (n_sims - 1));

    for (int32_t day = 1; day <= horizon_days; ++day) {
        const double net_sched = net_scheduled_by_day[day - 1];
        const bool check_hazard = (day <= days_to_payday);

        for (int32_t s = 0; s < n_sims; ++s) {
            const double sampled_spend = daily_disc_pool[rng.next_index(static_cast<size_t>(pool_size))];
            const double next_bal = current_balances[static_cast<size_t>(s)] + net_sched - sampled_spend;
            current_balances[static_cast<size_t>(s)] = next_bal;
            sorted_col[static_cast<size_t>(s)] = next_bal;
            if (check_hazard && next_bal < static_cast<double>(safe_threshold)) {
                breached[static_cast<size_t>(s)] = 1;
            }
        }

        std::nth_element(sorted_col.begin(), sorted_col.begin() + idx10, sorted_col.end());
        const double val10 = sorted_col[idx10];
        std::nth_element(sorted_col.begin(), sorted_col.begin() + idx50, sorted_col.end());
        const double val50 = sorted_col[idx50];
        std::nth_element(sorted_col.begin(), sorted_col.begin() + idx90, sorted_col.end());
        const double val90 = sorted_col[idx90];

        out_p10[day] = static_cast<int64_t>(std::llround(val10));
        out_p50[day] = static_cast<int64_t>(std::llround(val50));
        out_p90[day] = static_cast<int64_t>(std::llround(val90));
    }

    int32_t breach_count = 0;
    for (uint8_t b : breached) {
        breach_count += b;
    }
    return static_cast<double>(breach_count) / static_cast<double>(n_sims);
}

// 3. Exact Closed-Form MinT (Minimum Trace) Optimal Hierarchical Reconciliation:
//    Given bottom-level category forecasts y_hat[i] with variances var_b[i] and top-level
//    forecast y_top with variance var_top, reconciles y_tilde[i] so sum(y_tilde) == y_reconciled_total.
int64_t cpp_mint_reconcile(
    const double* y_hat,
    const double* var_b,
    int32_t n_cats,
    double y_top,
    double var_top,
    int64_t* out_reconciled
) {
    if (n_cats <= 0) {
        return 0;
    }
    double sum_b = 0.0;
    double sum_var = std::max(1.0, var_top);
    for (int32_t i = 0; i < n_cats; ++i) {
        sum_b += y_hat[i];
        sum_var += std::max(1.0, var_b[i]);
    }
    const double discrepancy = y_top - sum_b;
    int64_t total_reconciled = 0;
    for (int32_t i = 0; i < n_cats; ++i) {
        const double weight = std::max(1.0, var_b[i]) / sum_var;
        const int64_t adjusted = std::max<int64_t>(0, std::llround(y_hat[i] + weight * discrepancy));
        out_reconciled[i] = adjusted;
        total_reconciled += adjusted;
    }
    return total_reconciled;
}

// 4. Page's Upper CUSUM Change-Point Statistic in C++
double cpp_page_cusum(
    const double* series,
    int32_t n,
    double baseline_mean,
    double baseline_std,
    double allowance_k
) {
    if (n <= 0) {
        return 0.0;
    }
    const double sigma = std::max(baseline_std, 1.0);
    double s_pos = 0.0;
    for (int32_t i = 0; i < n; ++i) {
        const double z = (series[i] - baseline_mean) / sigma;
        s_pos = std::max(0.0, s_pos + z - allowance_k);
    }
    return s_pos;
}

}  // extern "C"
