"""Python ctypes bridge to the compiled C++20 `libquantcore` native library.

Automatically compiles `quantcore.cpp` with `clang++` or `g++` (`-std=c++20 -O3`) if not yet built,
and provides exact mathematical formula fallbacks if run on a host without a C++ compiler.
"""

from __future__ import annotations

import ctypes
import math
import platform
import shutil
import subprocess
import time
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import numpy as np

_HERE = Path(__file__).resolve().parent
_CPP_SRC = _HERE / "quantcore.cpp"
_LIB_EXT = ".dylib" if platform.system() == "Darwin" else ".so"
_LIB_PATH = _HERE / f"libquantcore{_LIB_EXT}"


@dataclass(frozen=True, slots=True)
class CppMonteCarloResult:
    p10_by_day: list[int]
    p50_by_day: list[int]
    p90_by_day: list[int]
    mc_hazard_prob: float
    wald_exact_prob: float
    execution_time_us: float
    engine: str  # "C++20 Native (libquantcore -O3)" | "Python/NumPy Vectorized Fallback"
    n_simulations: int


@lru_cache(maxsize=1)
def _load_quantcore() -> ctypes.CDLL | None:
    if not _LIB_PATH.exists() or (
        _CPP_SRC.exists() and _CPP_SRC.stat().st_mtime > _LIB_PATH.stat().st_mtime
    ):
        compiler = shutil.which("clang++") or shutil.which("g++")
        if compiler and _CPP_SRC.exists():
            try:
                subprocess.run(
                    [
                        compiler,
                        "-std=c++20",
                        "-O3",
                        "-shared",
                        "-fPIC",
                        str(_CPP_SRC),
                        "-o",
                        str(_LIB_PATH),
                    ],
                    check=True,
                    capture_output=True,
                    timeout=20,
                )
            except Exception:
                return None

    if not _LIB_PATH.exists():
        return None

    try:
        lib = ctypes.CDLL(str(_LIB_PATH))

        lib.cpp_wald_first_passage_prob.argtypes = [
            ctypes.c_double,
            ctypes.c_double,
            ctypes.c_double,
            ctypes.c_double,
            ctypes.c_double,
        ]
        lib.cpp_wald_first_passage_prob.restype = ctypes.c_double

        lib.cpp_monte_carlo_runway.argtypes = [
            ctypes.c_int64,
            ctypes.c_int64,
            ctypes.POINTER(ctypes.c_double),
            ctypes.c_int32,
            ctypes.POINTER(ctypes.c_double),
            ctypes.c_int32,
            ctypes.c_int32,
            ctypes.c_int32,
            ctypes.c_uint64,
            ctypes.POINTER(ctypes.c_int64),
            ctypes.POINTER(ctypes.c_int64),
            ctypes.POINTER(ctypes.c_int64),
        ]
        lib.cpp_monte_carlo_runway.restype = ctypes.c_double

        lib.cpp_mint_reconcile.argtypes = [
            ctypes.POINTER(ctypes.c_double),
            ctypes.POINTER(ctypes.c_double),
            ctypes.c_int32,
            ctypes.c_double,
            ctypes.c_double,
            ctypes.POINTER(ctypes.c_int64),
        ]
        lib.cpp_mint_reconcile.restype = ctypes.c_int64

        lib.cpp_page_cusum.argtypes = [
            ctypes.POINTER(ctypes.c_double),
            ctypes.c_int32,
            ctypes.c_double,
            ctypes.c_double,
            ctypes.c_double,
        ]
        lib.cpp_page_cusum.restype = ctypes.c_double
        return lib
    except Exception:
        return None


def is_cpp_kernel_active() -> bool:
    return _load_quantcore() is not None


def wald_first_passage_probability(
    current_balance: float,
    safe_threshold: float,
    daily_net_drift: float,
    daily_volatility: float,
    horizon_days: float,
) -> float:
    """Exact closed-form Bachelier-Wald Brownian motion with drift barrier-crossing probability."""
    lib = _load_quantcore()
    if lib is not None:
        return float(
            lib.cpp_wald_first_passage_prob(
                current_balance,
                safe_threshold,
                daily_net_drift,
                daily_volatility,
                horizon_days,
            )
        )

    if current_balance <= safe_threshold:
        return 1.0
    if horizon_days <= 0.0 or daily_volatility <= 1e-6:
        return 1.0 if (current_balance + daily_net_drift * horizon_days) <= safe_threshold else 0.0

    b = current_balance - safe_threshold
    denom = daily_volatility * math.sqrt(horizon_days)
    z1 = (-b - daily_net_drift * horizon_days) / denom
    z2 = (-b + daily_net_drift * horizon_days) / denom
    phi1 = 0.5 * (1.0 + math.erf(z1 / math.sqrt(2.0)))
    phi2 = 0.5 * (1.0 + math.erf(z2 / math.sqrt(2.0)))
    exp_arg = max(-60.0, min(60.0, (-2.0 * daily_net_drift * b) / (daily_volatility**2)))
    return max(0.0, min(1.0, phi1 + math.exp(exp_arg) * phi2))


def run_cpp_monte_carlo_runway(
    *,
    initial_balance: int,
    safe_threshold: int,
    daily_disc_pool: list[float],
    net_scheduled_by_day: list[float],
    days_to_payday: int,
    n_sims: int = 10000,
    seed: int = 20261027,
) -> CppMonteCarloResult:
    """Execute 10,000 Monte Carlo paths + exact closed-form Wald formula via C++20 native kernel."""
    horizon_days = len(net_scheduled_by_day)
    pool = daily_disc_pool or [150000.0]

    # Drift and volatility parameters for the exact closed-form Wald barrier equation
    avg_sched_before_payday = (
        float(np.mean(net_scheduled_by_day[: max(1, days_to_payday)]))
        if net_scheduled_by_day
        else 0.0
    )
    mean_disc = float(np.mean(pool))
    std_disc = max(float(np.std(pool)), 0.15 * mean_disc, 100.0)
    daily_net_drift = avg_sched_before_payday - mean_disc

    lib = _load_quantcore()
    t0 = time.perf_counter()

    if lib is not None:
        pool_arr = (ctypes.c_double * len(pool))(*pool)
        sched_arr = (ctypes.c_double * horizon_days)(*net_scheduled_by_day)
        out_len = horizon_days + 1
        p10_arr = (ctypes.c_int64 * out_len)()
        p50_arr = (ctypes.c_int64 * out_len)()
        p90_arr = (ctypes.c_int64 * out_len)()

        mc_prob = float(
            lib.cpp_monte_carlo_runway(
                initial_balance,
                safe_threshold,
                pool_arr,
                len(pool),
                sched_arr,
                horizon_days,
                days_to_payday,
                n_sims,
                seed,
                p10_arr,
                p50_arr,
                p90_arr,
            )
        )
        wald_prob = float(
            lib.cpp_wald_first_passage_prob(
                float(initial_balance),
                float(safe_threshold),
                daily_net_drift,
                std_disc,
                float(days_to_payday),
            )
        )
        elapsed_us = (time.perf_counter() - t0) * 1_000_000.0
        return CppMonteCarloResult(
            p10_by_day=list(p10_arr),
            p50_by_day=list(p50_arr),
            p90_by_day=list(p90_arr),
            mc_hazard_prob=mc_prob,
            wald_exact_prob=wald_prob,
            execution_time_us=round(elapsed_us, 1),
            engine="C++20 Native (libquantcore -O3)",
            n_simulations=n_sims,
        )

    # Vectorized NumPy fallback if C++ shared library is unavailable
    rng = np.random.default_rng(seed)
    pool_np = np.asarray(pool, dtype=float)
    paths = np.zeros((n_sims, horizon_days + 1), dtype=float)
    paths[:, 0] = float(initial_balance)
    for d in range(1, horizon_days + 1):
        sampled = rng.choice(pool_np, size=n_sims, replace=True)
        paths[:, d] = paths[:, d - 1] + net_scheduled_by_day[d - 1] - sampled

    min_before = np.min(paths[:, : days_to_payday + 1], axis=1)
    mc_prob = float(np.mean(min_before < safe_threshold))
    wald_prob = wald_first_passage_probability(
        float(initial_balance),
        float(safe_threshold),
        daily_net_drift,
        std_disc,
        float(days_to_payday),
    )
    elapsed_us = (time.perf_counter() - t0) * 1_000_000.0
    return CppMonteCarloResult(
        p10_by_day=[round(x) for x in np.percentile(paths, 10, axis=0)],
        p50_by_day=[round(x) for x in np.percentile(paths, 50, axis=0)],
        p90_by_day=[round(x) for x in np.percentile(paths, 90, axis=0)],
        mc_hazard_prob=mc_prob,
        wald_exact_prob=wald_prob,
        execution_time_us=round(elapsed_us, 1),
        engine="Python/NumPy Vectorized Fallback",
        n_simulations=n_sims,
    )


def cpp_page_cusum_stat(
    series: list[int], baseline_mean: float, baseline_std: float, allowance_k: float = 0.5
) -> float:
    lib = _load_quantcore()
    if lib is not None and series:
        arr = (ctypes.c_double * len(series))(*[float(x) for x in series])
        return float(lib.cpp_page_cusum(arr, len(series), baseline_mean, baseline_std, allowance_k))
    sigma = max(baseline_std, 1.0)
    s_pos = 0.0
    for val in series:
        s_pos = max(0.0, s_pos + (val - baseline_mean) / sigma - allowance_k)
    return s_pos


def cpp_mint_reconcile_forecasts(
    y_hat: list[float],
    var_b: list[float],
    y_top: float,
    var_top: float,
) -> tuple[int, list[int]]:
    """Reconcile bottom-level category forecasts with top-level total via C++20 MinT kernel."""
    n_cats = len(y_hat)
    if n_cats == 0:
        return 0, []
    lib = _load_quantcore()
    if lib is not None:
        y_arr = (ctypes.c_double * n_cats)(*y_hat)
        v_arr = (ctypes.c_double * n_cats)(*var_b)
        out_arr = (ctypes.c_int64 * n_cats)()
        total = int(
            lib.cpp_mint_reconcile(
                y_arr,
                v_arr,
                n_cats,
                float(y_top),
                float(var_top),
                out_arr,
            )
        )
        return total, list(out_arr)

    sum_b = sum(y_hat)
    sum_var = max(1.0, var_top) + sum(max(1.0, v) for v in var_b)
    discrepancy = y_top - sum_b
    reconciled: list[int] = []
    for y_i, v_i in zip(y_hat, var_b, strict=False):
        weight = max(1.0, v_i) / sum_var
        adj = max(0, round(y_i + weight * discrepancy))
        reconciled.append(adj)
    return sum(reconciled), reconciled
