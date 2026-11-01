"""The comparison figures the workbook and the report charts share, computed from `results/`.

A rate is (point, low, high): the mean over rows with a 95% bootstrap interval that resamples questions.
A paired difference is the same over the rows two runs share, question by question.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "promptfoo"))

import summary  # noqa: E402

RESULTS = ROOT / "results"
Rate = tuple[float | None, float | None, float | None]
LAYERS = ("text", "mixed", "scanned")
SEARCHES = {
    "retrieval-hybrid": "Hybrid",
    "retrieval-keyword": "Keyword only",
    "retrieval-vector": "Vector only",
}
RANKING = {
    "recall_at_10": "Recall@10",
    "ndcg_at_10": "nDCG@10",
    "mrr_at_10": "MRR@10",
    "passage_recall_at_10": "Passage recall@10",
}
MODES = {"baseline-v3-standard": "Standard", "baseline-v3-grounded": "Grounded"}
CHAT = {
    "pass_all": "Rows passing every check",
    "context_recall": "Context recall",
    "context_precision": "Context precision",
    "correctness": "Correctness (Luna)",
    "correctness_sol": "Correctness (Sol)",
    "completeness": "Completeness (Luna)",
    "completeness_sol": "Completeness (Sol)",
    "faithfulness": "Faithfulness",
}
VARIANTS = {
    "ablation-v0": "V0 release",
    "ablation-v1a": "V1 no rewrite, run 1",
    "ablation-v1b": "V1 no rewrite, run 2",
    "ablation-v6": "V6 no adjacent chunks",
}
ABLATION = {
    "pass_all": "Rows passing every check",
    "correctness": "Correctness",
    "faithfulness": "Faithfulness",
    "behaviour": "Behaviour",
}


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def score(row: dict, metric: str) -> float | None:
    if metric == "pass_all":
        return None if row["passAll"] is None else float(row["passAll"])
    entry = row["metrics"].get(metric)
    return None if entry is None else float(entry["score"])


def by_question(rows: list[dict], value: Callable[[dict], float | None]) -> dict[str, list[float]]:
    clusters: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        v = value(row)
        if v is not None:
            clusters[row["id"]].append(v)
    return clusters


def interval(clusters: dict[str, list[float]]) -> Rate:
    return summary.bootstrap(clusters, summary.mean)


def pct(value: float | None) -> float | None:
    return None if value is None else round(value, 4)


def rate(rows: list[dict], metric: str) -> Rate:
    return interval(by_question(rows, lambda r: score(r, metric)))


def paired(rows: list[dict], against: list[dict], metric: str) -> tuple[Rate, int]:
    """rows minus against on the rows both have, with its interval, and the number of rows."""
    other = {(r["id"], r["role"]): r for r in against}
    clusters: dict[str, list[float]] = defaultdict(list)
    for r in rows:
        twin = other.get((r["id"], r["role"]))
        if twin and score(r, metric) is not None and score(twin, metric) is not None:
            clusters[r["id"]].append(score(r, metric) - score(twin, metric))
    return interval(clusters), sum(len(v) for v in clusters.values())


def verdict(difference: Rate, more: str = "higher", less: str = "lower") -> str:
    _, low, high = difference
    if low is None or high is None:
        return ""
    if low > 0:
        return f"{more.capitalize()}: the interval is above zero"
    if high < 0:
        return f"{less.capitalize()}: the interval is below zero"
    return "No measured difference: the interval includes zero"


def load_runs() -> dict[str, list[dict]]:
    names = list(SEARCHES) + list(MODES) + list(VARIANTS)
    return {name: read_jsonl(RESULTS / f"{name}.jsonl") for name in names}


def comparisons(runs: dict[str, list[dict]], layers: dict[str, str]) -> dict:
    """Every comparison the workbook's Comparison sheet and the report charts show."""
    hybrid = runs["retrieval-hybrid"]
    retrieval = {
        metric: {name: rate(runs[name], metric) for name in SEARCHES} for metric in RANKING
    }
    retrieval_diff = {
        (metric, other): paired(hybrid, runs[other], metric)[0]
        for metric in RANKING
        for other in ("retrieval-keyword", "retrieval-vector")
    }
    chat = {metric: {name: rate(runs[name], metric) for name in MODES} for metric in CHAT}
    grounded = runs["baseline-v3-grounded"]

    def in_layer(rows: list[dict], layer: str) -> list[dict]:
        return [r for r in rows if layers.get(r["id"]) == layer]

    by_layer = {
        "Extraction coverage": {
            layer: rate(in_layer(hybrid, layer), "extraction_coverage") for layer in LAYERS
        },
        "Recall@10": {layer: rate(in_layer(hybrid, layer), "recall_at_10") for layer in LAYERS},
        "Context recall": {
            layer: rate(in_layer(grounded, layer), "context_recall") for layer in LAYERS
        },
        "Correctness": {layer: rate(in_layer(grounded, layer), "correctness") for layer in LAYERS},
        "Completeness": {
            layer: rate(in_layer(grounded, layer), "completeness") for layer in LAYERS
        },
    }
    stages = [
        ("1 Extraction", "Extraction coverage", rate(hybrid, "extraction_coverage")),
        ("2 Retrieval", "Recall@10", rate(hybrid, "recall_at_10")),
        ("2 Retrieval", "Passage recall@10", rate(hybrid, "passage_recall_at_10")),
        ("3 Context", "Context recall", rate(grounded, "context_recall")),
        ("4 Answer", "Correctness", rate(grounded, "correctness")),
        ("4 Answer", "Completeness", rate(grounded, "completeness")),
        ("4 Answer", "Faithfulness", rate(grounded, "faithfulness")),
    ]
    v0 = runs["ablation-v0"]
    ablation = {
        name: {metric: rate(runs[name], metric) for metric in ABLATION} for name in VARIANTS
    }
    for name in VARIANTS:
        near = [r for r in runs[name] if r["category"] == "near_miss"]
        ablation[name]["near_miss"] = rate(near, "pass_all")
    ablation_diff = {
        name: paired(runs[name], v0, "pass_all")[0] for name in VARIANTS if name != "ablation-v0"
    }
    return {
        "retrieval": retrieval,
        "retrieval_diff": retrieval_diff,
        "chat": chat,
        "by_layer": by_layer,
        "stages": stages,
        "ablation": ablation,
        "ablation_diff": ablation_diff,
    }
