"""Summarizes promptfoo exports: each metric's mean with a 95% bootstrap interval, by stratum, the turn time,
paired differences between two runs and agreement between two judges.

    python summary.py run.json [regrade.json ...] [--against other.json] [--by key=value,...]

Several exports of the same replies (the baseline and its stage re-grade) merge into one row per
question, role and mode.

Exports come from `promptfoo export eval <id> -o run.json`. Intervals resample questions, not rows: one
question is asked by several roles, so its rows are not independent.
"""

from __future__ import annotations

import argparse
import json
import math
import random
from collections import defaultdict
from collections.abc import Callable, Iterable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = ROOT / "corpus" / "questions.jsonl"
MANIFEST = ROOT / "corpus" / "manifest.json"
RESAMPLES = 10_000
SEED = 230
# Pairs of metrics graded by two judges on the same rows.
AGREEMENT = [("correctness", "correctness_sol"), ("completeness", "completeness_sol")]

Row = dict[str, object]


def text_layers() -> dict[str, str]:
    """Question id → text layer of its evidence documents: text, scanned, or mixed."""
    layer = {
        d["id"]: d["textLayer"]
        for d in json.loads(MANIFEST.read_text(encoding="utf-8"))["documents"]
    }
    out = {}
    for line in QUESTIONS.read_text(encoding="utf-8").splitlines():
        question = json.loads(line)
        layers = {layer[e["document"]] for e in question.get("evidence") or []}
        if layers:
            out[question["id"]] = (
                layers.pop() if len(layers) == 1 and layers <= {"text", "scanned"} else "mixed"
            )
    return out


def load(path: Path, layers: dict[str, str]) -> list[Row]:
    """One row per test: its metadata, the score of each named metric and the turn time."""
    rows = []
    for result in json.loads(path.read_text(encoding="utf-8"))["results"]["results"]:
        meta = result["testCase"].get("metadata") or {}
        output = (result.get("response") or {}).get("output")
        components = (result.get("gradingResult") or {}).get("componentResults") or []
        scores: dict[str, float] = {}
        passes: dict[str, bool] = {}
        for component in components:
            metric = (component.get("assertion") or {}).get("metric")
            if metric:
                scores[metric] = float(component.get("score") or 0)
                passes[metric] = bool(component.get("pass"))
        # failureReason 2 is a provider or runtime error; 1, a failed assertion, still counts.
        if components and result.get("failureReason") != 2:
            scores["pass_all"] = 1.0 if result.get("success") else 0.0
        rows.append(
            {
                "id": meta.get("id"),
                "role": meta.get("role"),
                "category": meta.get("category"),
                "split": meta.get("split"),
                "mode": meta.get("mode"),
                "layer": layers.get(str(meta.get("id")), "none"),
                "scores": scores,
                "passes": passes,
                "seconds": output.get("seconds") if isinstance(output, dict) else None,
            }
        )
    return rows


def bootstrap(clusters: dict[str, list[float]], statistic: Callable[[list[float]], float]) -> tuple:
    """Point value and 95% interval of `statistic`, resampling whole clusters."""
    keys = sorted(clusters)
    values = [v for k in keys for v in clusters[k]]
    if not values:
        return None, None, None
    rng = random.Random(SEED)  # noqa: S311 - a seeded resample, not a secret
    draws = []
    for _ in range(RESAMPLES):
        sample = [v for k in rng.choices(keys, k=len(keys)) for v in clusters[k]]
        draws.append(statistic(sample) if sample else 0.0)
    draws.sort()
    return statistic(values), draws[int(0.025 * RESAMPLES)], draws[int(0.975 * RESAMPLES) - 1]


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def percentile(q: float) -> Callable[[list[float]], float]:
    def at(values: list[float]) -> float:
        # Nearest rank: the smallest value with at least q of the values at or below it.
        ordered = sorted(values)
        return ordered[max(0, math.ceil(q * len(ordered)) - 1)]

    return at


def by_question(
    rows: Iterable[Row], value: Callable[[Row], float | None]
) -> dict[str, list[float]]:
    clusters: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        v = value(row)
        if v is not None:
            clusters[str(row["id"])].append(v)
    return clusters


def fmt(
    point: float | None, low: float | None, high: float | None, n: int, scale: float = 100
) -> str:
    if point is None:
        return "–"
    unit = "%" if scale == 100 else " s"
    return f"{point * scale:.1f}{unit} [{low * scale:.1f}, {high * scale:.1f}] (n={n})"


def metric_table(rows: list[Row], strata: list[str]) -> list[str]:
    metrics = sorted({m for r in rows for m in r["scores"]})
    lines = [
        "| Metric | All | " + " | ".join(strata) + " |",
        "| --- | --- |" + " --- |" * len(strata),
    ]
    for metric in metrics:
        cells = []
        for subset in [rows] + [[r for r in rows if f"{r[k]}" == v] for k, v in _strata(strata)]:
            clusters = by_question(subset, lambda r, m=metric: r["scores"].get(m))
            n = sum(len(v) for v in clusters.values())
            cells.append(fmt(*bootstrap(clusters, mean), n))
        lines.append(f"| {metric} | " + " | ".join(cells) + " |")
    return lines


def _strata(strata: list[str]) -> list[tuple[str, str]]:
    return [tuple(s.split("=", 1)) for s in strata]  # type: ignore[misc]


def latency(rows: list[Row]) -> list[str]:
    clusters = by_question(
        rows, lambda r: r["seconds"] if isinstance(r["seconds"], int | float) else None
    )
    n = sum(len(v) for v in clusters.values())
    return [
        f"Turn time p50: {fmt(*bootstrap(clusters, percentile(0.5)), n, scale=1)}",
        f"Turn time p95: {fmt(*bootstrap(clusters, percentile(0.95)), n, scale=1)}",
    ]


def kappa(pairs: list[tuple[bool, bool]]) -> tuple[float, float]:
    """Observed agreement and Cohen's kappa of two judges' pass/fail on the same rows."""
    n = len(pairs)
    observed = sum(a == b for a, b in pairs) / n
    first, second = sum(a for a, _ in pairs) / n, sum(b for _, b in pairs) / n
    expected = first * second + (1 - first) * (1 - second)
    return observed, (observed - expected) / (1 - expected) if expected < 1 else 1.0


def agreement(rows: list[Row]) -> list[str]:
    lines = []
    for first, second in AGREEMENT:
        pairs = [
            (bool(r["passes"][first]), bool(r["passes"][second]))
            for r in rows
            if first in r["passes"] and second in r["passes"]
        ]
        if pairs:
            observed, k = kappa(pairs)
            lines.append(
                f"{first} vs {second}: agreement {observed:.1%}, kappa {k:.2f} (n={len(pairs)})"
            )
    return lines


def paired(rows: list[Row], against: list[Row]) -> list[str]:
    """Run minus `against` on the rows both completed, per metric, clustered by question."""
    other = {(r["id"], r["role"]): r for r in against}
    lines = ["| Metric | Difference |", "| --- | --- |"]
    for metric in sorted({m for r in rows for m in r["scores"]}):
        clusters: dict[str, list[float]] = defaultdict(list)
        for row in rows:
            twin = other.get((row["id"], row["role"]))
            if twin and metric in row["scores"] and metric in twin["scores"]:
                clusters[str(row["id"])].append(row["scores"][metric] - twin["scores"][metric])
        n = sum(len(v) for v in clusters.values())
        lines.append(f"| {metric} | {fmt(*bootstrap(clusters, mean), n)} |")
    return lines


def merged(runs: list[list[Row]]) -> list[Row]:
    """Rows of several gradings of the same replies as one row per (question, role, mode): a stage
    re-grade adds its metrics to the baseline's. A later run's metric wins; pass_all is the first run's,
    since a re-grade's checks are not the baseline's."""
    rows: dict[tuple, Row] = {}
    for run in runs:
        for row in run:
            key = (row["id"], row["role"], row["mode"])
            if key not in rows:
                rows[key] = row
                continue
            first = rows[key]
            first["scores"] = {**row["scores"], **first["scores"]} | {
                m: v for m, v in row["scores"].items() if m != "pass_all"
            }
            first["passes"] = {**first["passes"], **row["passes"]}
    return list(rows.values())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("runs", type=Path, nargs="+")
    parser.add_argument(
        "--against",
        type=Path,
        nargs="+",
        help="a second run (and its retries) for paired differences",
    )
    parser.add_argument(
        "--by",
        default="layer=text,layer=mixed,layer=scanned,split=dev,split=test",
        help="comma-separated key=value strata",
    )
    args = parser.parse_args()
    layers = text_layers()
    rows = merged([load(path, layers) for path in args.runs])
    strata = [s for s in args.by.split(",") if s]
    out = [f"Rows: {len(rows)}", ""] + metric_table(rows, strata) + [""] + latency(rows)
    out += [""] + agreement(rows)
    if args.against:
        against = merged([load(path, layers) for path in args.against])
        out += ["", f"Paired against {args.against[0].name}:", ""] + paired(rows, against)
    print("\n".join(out))


if __name__ == "__main__":
    main()
