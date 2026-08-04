#!/usr/bin/env python3
"""
Chiron-style Adaptive Batch Size Simulator v3

Key improvements from v2 simulation findings:
  - Hysteresis: after halving, wait 5 cycles before allowing upscale
  - Consecutive checks: require 2 consecutive passes before upscaling
  - Multiplicative upscale: batch *= 1.5 (not slow EWMA creep)
  - Halving downscale: aggressive recovery from errors
  - Larger window: 50 samples (not 20) to avoid false halving triggers
  - Longer adjust interval: 20 batches (not 10) for stability

python tools/adaptive_batch_sim.py
"""

import random
from dataclasses import dataclass, field
from typing import List, Tuple

# ============================================================
TOTAL_METHODS = 5051
INITIAL_BATCH_SIZE = 20
MIN_BATCH_SIZE = 3
MAX_BATCH_SIZE = 50
ADJUST_EVERY_N_BATCHES = 20
EWMA_ALPHA = 0.3
UPSCALE_MULTIPLIER = 1.5
HYSTERESIS_CYCLES = 5
UP_REQUIRE_CONSECUTIVE = 2
SLIDING_WINDOW_SIZE = 50

SLO_LATENCY_PER_METHOD_MS = 200
SLO_ERROR_RATE = 0.02

PROMPT_OVERHEAD_TOKENS = 500
TOKENS_PER_METHOD = 150
MAX_TOKENS = 1024
TOKEN_OVERFLOW_RATIO = 0.7

COOLDOWN_BATCHES = 3
SLOW_START_BATCHES = 5

BASE_LATENCY_MS = 80
BATCH_OVERHEAD_MS = 200
PER_METHOD_PROCESSING_MS = 30
BASE_ERROR_RATE = 0.005
LARGE_BATCH_PENALTY_START = 35
LARGE_BATCH_ERROR_MULTIPLIER = 3

METHOD_COMPLEXITY_WEIGHTS = [0.6, 0.3, 0.1]
COMPLEXITY_MULTIPLIERS = [0.5, 1.0, 3.0]


@dataclass
class SlidingWindow:
    max_size: int
    latencies: List[float] = field(default_factory=list)
    errors: List[bool] = field(default_factory=list)

    def record(self, latency_ms: float, error: bool):
        self.latencies.append(latency_ms)
        self.errors.append(error)
        if len(self.latencies) > self.max_size:
            self.latencies.pop(0)
            self.errors.pop(0)

    def avg_latency_per_method(self, batch_sizes: List[int]) -> float:
        if not self.latencies:
            return 0
        n = min(len(self.latencies), len(batch_sizes))
        total = sum(self.latencies[-n:])
        total_methods = sum(batch_sizes[-n:])
        return total / total_methods if total_methods > 0 else 0

    def error_rate(self) -> float:
        if not self.errors:
            return 0
        return sum(1 for e in self.errors if e) / len(self.errors)

    @property
    def count(self) -> int:
        return len(self.latencies)


@dataclass
class AdaptiveBatchController:
    batch_size: int = INITIAL_BATCH_SIZE
    window: SlidingWindow = field(default_factory=lambda: SlidingWindow(SLIDING_WINDOW_SIZE))
    batch_sizes_in_window: List[int] = field(default_factory=list)
    batches_since_adjust: int = 0
    cooldown_remaining: int = 0
    slow_start_step: int = 0
    cycles_since_halving: int = HYSTERESIS_CYCLES + 1
    consecutive_up_checks: int = 0

    def estimate_tokens(self, count: int) -> int:
        return PROMPT_OVERHEAD_TOKENS + count * TOKENS_PER_METHOD

    def preflight_check(self, desired_count: int) -> int:
        estimated = self.estimate_tokens(desired_count)
        if estimated > MAX_TOKENS * TOKEN_OVERFLOW_RATIO:
            safe = int(MAX_TOKENS * TOKEN_OVERFLOW_RATIO * desired_count / estimated)
            return max(MIN_BATCH_SIZE, safe)
        return desired_count

    def record_batch(self, batch_size: int, latency_ms: float, error: bool):
        self.window.record(latency_ms, error)
        self.batch_sizes_in_window.append(batch_size)
        if len(self.batch_sizes_in_window) > self.window.max_size:
            self.batch_sizes_in_window.pop(0)

        if error and self.cooldown_remaining <= 0:
            self.cooldown_remaining = COOLDOWN_BATCHES
            self.slow_start_step = 1

        if self.cooldown_remaining > 0:
            self.cooldown_remaining -= 1
            if self.cooldown_remaining == 0:
                self.batch_size = max(MIN_BATCH_SIZE, self.batch_size // 2)

        self.batches_since_adjust += 1

    def effective_batch_size(self) -> int:
        if self.cooldown_remaining > 0:
            return MIN_BATCH_SIZE
        if 0 < self.slow_start_step <= SLOW_START_BATCHES:
            target = min(self.batch_size, MIN_BATCH_SIZE + self.slow_start_step)
            self.slow_start_step += 1
            return target
        return self.batch_size

    def should_adjust(self) -> bool:
        return (self.batches_since_adjust >= ADJUST_EVERY_N_BATCHES
                and self.window.count >= ADJUST_EVERY_N_BATCHES
                and self.cooldown_remaining <= 0
                and self.slow_start_step > SLOW_START_BATCHES)

    def adjust(self) -> Tuple[int, str]:
        self.batches_since_adjust = 0
        self.cycles_since_halving += 1

        error_rate = self.window.error_rate()
        avg_lat = self.window.avg_latency_per_method(self.batch_sizes_in_window)

        # --- 下调：任一触发 → halving ---
        if error_rate > SLO_ERROR_RATE:
            old = self.batch_size
            self.batch_size = max(MIN_BATCH_SIZE, self.batch_size // 2)
            self.cycles_since_halving = 0
            self.consecutive_up_checks = 0
            return self.batch_size, (
                f"[halving] err={error_rate:.1%}>{SLO_ERROR_RATE:.0%} "
                f"{old}->{self.batch_size} (冷却{self.cycles_since_halving}/{HYSTERESIS_CYCLES})")

        if avg_lat > SLO_LATENCY_PER_METHOD_MS:
            old = self.batch_size
            self.batch_size = max(MIN_BATCH_SIZE, self.batch_size // 2)
            self.cycles_since_halving = 0
            self.consecutive_up_checks = 0
            return self.batch_size, (
                f"[halving] lat={avg_lat:.0f}ms>{SLO_LATENCY_PER_METHOD_MS}ms "
                f"{old}->{self.batch_size} (冷却{self.cycles_since_halving}/{HYSTERESIS_CYCLES})")

        # --- 上调：hysteresis + consecutive checks ---
        if self.cycles_since_halving < HYSTERESIS_CYCLES:
            return self.batch_size, (
                f"[hysteresis] 冷却中 ({self.cycles_since_halving}/{HYSTERESIS_CYCLES})")

        can_upscale = (error_rate < SLO_ERROR_RATE * 0.5
                       and avg_lat < SLO_LATENCY_PER_METHOD_MS * 0.7)

        if can_upscale:
            self.consecutive_up_checks += 1
            if self.consecutive_up_checks >= UP_REQUIRE_CONSECUTIVE:
                old = self.batch_size
                self.batch_size = min(MAX_BATCH_SIZE,
                                      max(MIN_BATCH_SIZE, int(self.batch_size * UPSCALE_MULTIPLIER)))
                self.consecutive_up_checks = 0
                return self.batch_size, (
                    f"[upscale x{UPSCALE_MULTIPLIER}] err={error_rate:.1%} lat={avg_lat:.0f}ms "
                    f"{old}->{self.batch_size}")
            else:
                return self.batch_size, (
                    f"[up-wait {self.consecutive_up_checks}/{UP_REQUIRE_CONSECUTIVE}] "
                    f"err={error_rate:.1%} lat={avg_lat:.0f}ms")
        else:
            self.consecutive_up_checks = 0
            return self.batch_size, "保持"


def simulate_api_call(batch_size: int) -> Tuple[float, bool]:
    complexities = random.choices([0, 1, 2], weights=METHOD_COMPLEXITY_WEIGHTS, k=batch_size)
    total = sum(PER_METHOD_PROCESSING_MS * COMPLEXITY_MULTIPLIERS[c] for c in complexities)
    jitter = random.uniform(0.7, 1.3)
    latency = (BATCH_OVERHEAD_MS + total) * jitter

    err = BASE_ERROR_RATE
    if batch_size > LARGE_BATCH_PENALTY_START:
        err *= LARGE_BATCH_ERROR_MULTIPLIER
    return latency, random.random() < err


def run_sim(label: str, init_batch: int, seed: int = 42) -> dict:
    random.seed(seed)
    ctrl = AdaptiveBatchController(batch_size=init_batch)
    remaining = TOTAL_METHODS
    total_batches = 0
    total_errors = 0
    total_time = 0.0
    history: List[Tuple[int, int, str]] = []

    while remaining > 0:
        eff = ctrl.effective_batch_size()
        actual = ctrl.preflight_check(min(eff, remaining))
        lat, err = simulate_api_call(actual)
        ctrl.record_batch(actual, lat, err)

        total_batches += 1
        total_time += lat
        if err:
            total_errors += 1
        remaining -= actual

        if ctrl.should_adjust():
            new_size, reason = ctrl.adjust()
            if 'halving' in reason or 'upscale' in reason:
                history.append((total_batches, new_size, reason))

    err_rate = total_errors / total_batches if total_batches else 0

    print(f"\n{'='*60}")
    print(f"[{label}]")
    print(f"  batch: {init_batch} -> {ctrl.batch_size} | batches: {total_batches} | time: {total_time/1000:.1f}s")
    print(f"  per-method: {total_time/TOTAL_METHODS:.1f}ms | errors: {err_rate:.1%} | adjusts: {len(history)}")

    if history and len(history) <= 15:
        for bn, bs, r in history:
            print(f"    #{bn}: batch={bs} {r}")

    return {
        "label": label, "initial": init_batch, "final": ctrl.batch_size,
        "total_batches": total_batches, "time_s": total_time / 1000,
        "error_rate": err_rate, "adjusts": len(history),
    }


def main():
    print("=" * 60)
    print("Chiron v3: hysteresis + consecutive-check + multiplicative upscale")
    print(f"  window={SLIDING_WINDOW_SIZE} interval={ADJUST_EVERY_N_BATCHES} "
          f"hysteresis={HYSTERESIS_CYCLES} up-consecutive={UP_REQUIRE_CONSECUTIVE}")
    print(f"  upscale=x{UPSCALE_MULTIPLIER} downscale=halving")

    results = [
        run_sim("标准 (20)", 20, 42),
    ]

    global BASE_ERROR_RATE
    orig = BASE_ERROR_RATE
    BASE_ERROR_RATE = 0.04
    results.append(run_sim("高错误率 4% (20)", 20, 42))
    BASE_ERROR_RATE = orig

    results.append(run_sim("激进起点 (50)", 50, 42))
    results.append(run_sim("保守起点 (5)", 5, 42))

    global LARGE_BATCH_ERROR_MULTIPLIER
    orig_p = LARGE_BATCH_ERROR_MULTIPLIER
    LARGE_BATCH_ERROR_MULTIPLIER = 8
    results.append(run_sim("大batch退化 x8 (30)", 30, 42))
    LARGE_BATCH_ERROR_MULTIPLIER = orig_p

    print(f"\n{'='*60}")
    print(f"{'场景':<25} {'init':>4} {'final':>5} {'batches':>7} {'time':>7} {'err%':>6} {'adj':>4}")
    print("-" * 65)
    for r in results:
        print(f"{r['label']:<25} {r['initial']:>4} {r['final']:>5} "
              f"{r['total_batches']:>7} {r['time_s']:>6.1f}s {r['error_rate']:>5.1%} {r['adjusts']:>4}")

    all_ok = True
    for r in results:
        issues = []
        if r['adjusts'] > 50:
            issues.append(f"too many adjusts ({r['adjusts']})")
        if r['error_rate'] > 0.08:
            issues.append(f"high error ({r['error_rate']:.1%})")
        print(f"  {'OK' if not issues else '!!'} {r['label']}: "
              f"{'; '.join(issues) if issues else 'stable'}")
        if issues:
            all_ok = False

    print(f"\n{'ALL PASS' if all_ok else 'SOME FAILURES - review params'}")


if __name__ == "__main__":
    main()
