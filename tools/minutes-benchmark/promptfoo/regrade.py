"""Scores stored minutes again after a change to the scoring alone, without having MemoryOS write them again.

    python3 regrade.py run.json regraded.json      # in the container, with OPENAI_API_KEY set for the judge

`run.json` is a promptfoo export; the output keeps its rows and replaces each grading, so `summary.py` reads it.
"""

from __future__ import annotations

import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import assertions

# Two at a time, as the runs: the judge shares the 9Router accounts with the model that writes the minutes.
WORKERS = 2


def regrade(row: dict) -> dict:
    """One row scored again; a row with no minutes, or one the judge could not score, is marked failed."""
    output = (row.get("response") or {}).get("output")
    if row.get("error") or not output:
        return row
    try:
        output = json.loads(output) if isinstance(output, str) else output
        result = assertions.score(output, {"vars": row["vars"]})
    except Exception as failure:  # noqa: BLE001 - one row's failure must not lose the others
        row["error"] = f"regrade failed: {type(failure).__name__}: {failure}"
        row["gradingResult"] = None
        return row
    row["gradingResult"] = {**result, "componentResults": [result]}
    row["namedScores"] = result["namedScores"]
    return row


def main() -> None:
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    rows = data["results"]["results"]
    with ThreadPoolExecutor(WORKERS) as pool:
        data["results"]["results"] = list(pool.map(regrade, rows))
    Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    print(f"{len(rows)} rows regraded -> {sys.argv[2]}")


if __name__ == "__main__":
    main()
