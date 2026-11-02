"""Summarises a minutes benchmark run exported by promptfoo (`promptfoo export eval <id> -o run.json`).

    python3 summary.py run.json [other.json ...]

For each metric: the mean over every meeting and repeat, and the spread of the per-repeat means (lowest to
highest), since one writing of the minutes differs from the next. Counts are summed, so a meeting with many
actions weighs more in the pooled rates than in the mean of per-meeting rates; both are shown for owners.
"""

from __future__ import annotations

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

METRICS = ("action_recall", "action_precision", "decision_recall", "decision_precision", "owner_accuracy",
           "owner_wrong", "duplicates", "quote_in_line")


def rows(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    results = data.get("results", data)
    return results.get("results", results) if isinstance(results, dict) else results


def summarise(path: Path) -> None:
    by_repeat: dict[int, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    pooled: dict[str, int] = defaultdict(int)
    failed = 0
    for row in rows(path):
        grading = row.get("gradingResult") or {}
        if row.get("error") or not grading.get("namedScores"):
            failed += 1
            continue
        repeat = row.get("repeatIndex", 0)
        for name, value in grading["namedScores"].items():
            by_repeat[repeat][name].append(value)
        for component in grading.get("componentResults") or [grading]:
            try:
                counts = json.loads(component.get("reason") or "{}").get("counts", {})
            except json.JSONDecodeError:
                continue
            for name, value in counts.items():
                pooled[name] += value
    print(f"{path.name}: {sum(len(v['action_recall']) for v in by_repeat.values())} scored, {failed} failed")
    for metric in METRICS:
        means = [statistics.fmean(values[metric]) for values in by_repeat.values() if values.get(metric)]
        if not means:
            continue
        print(f"  {metric:<19} {statistics.fmean(means):.3f}  (repeats {min(means):.3f}–{max(means):.3f})")
    if pooled.get("owner_checked"):
        checked = pooled["owner_checked"]
        print(f"  owners pooled       right {pooled.get('owner_right', 0)}/{checked}, wrong {pooled.get('owner_wrong', 0)},"
              f" missing {pooled.get('owner_missing', 0)}")


if __name__ == "__main__":
    for argument in sys.argv[1:]:
        summarise(Path(argument))
