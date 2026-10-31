"""Reduces promptfoo exports of one run to compact JSON lines kept in the repository: per row the question,
role, each metric's score and pass, what the model read and cited, the answer and the turn time.

    python export_rows.py <out.jsonl> run.json [regrade.json | retry.json ...]

Later exports of the same replies (a stage re-grade, the retry of error rows) merge into the first, as in
summary.py. Large provider payloads (section texts, indexed text) are left out.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ANSWER_CHARS = 2000


def rows(path: Path) -> list[dict]:
    out = []
    for result in json.loads(path.read_text(encoding="utf-8"))["results"]["results"]:
        meta = result["testCase"].get("metadata") or {}
        output = (result.get("response") or {}).get("output")
        output = output if isinstance(output, dict) else {}
        metrics = {}
        for component in (result.get("gradingResult") or {}).get("componentResults") or []:
            metric = (component.get("assertion") or {}).get("metric")
            if metric:
                metrics[metric] = {
                    "score": round(float(component.get("score") or 0), 4),
                    "pass": bool(component.get("pass")),
                    "reason": (component.get("reason") or "")[:300],
                }
        error = result.get("failureReason") == 2
        ranked = [r["document"] for r in output.get("ranked") or []][:10]
        out.append(
            {
                "id": meta.get("id"),
                "role": meta.get("role"),
                "mode": meta.get("mode"),
                "category": meta.get("category"),
                "split": meta.get("split"),
                "expect": meta.get("expect"),
                "error": (result.get("error") or "")[:300] if error else "",
                "passAll": None if error or not metrics else bool(result.get("success")),
                "metrics": metrics,
                "answer": (output.get("answer") or "")[:ANSWER_CHARS],
                "readDocuments": output.get("readDocuments") or [],
                "citedDocuments": output.get("citedDocuments") or [],
                "top10": ranked,
                "seconds": output.get("seconds"),
            }
        )
    return out


def merge(runs: list[list[dict]]) -> list[dict]:
    """A later export fills a row's error and adds metrics; the first export's pass verdict stays unless
    the first row was an error."""
    merged: dict[tuple, dict] = {}
    for run in runs:
        for row in run:
            key = (row["id"], row["role"], row["mode"])
            first = merged.get(key)
            if first is None:
                merged[key] = row
            elif first["error"] and not row["error"]:
                row["metrics"] = {**first["metrics"], **row["metrics"]}
                merged[key] = row
            elif not row["error"]:
                first["metrics"].update(row["metrics"])
    return list(merged.values())


def main() -> None:
    out = Path(sys.argv[1])
    merged = merge([rows(Path(p)) for p in sys.argv[2:]])
    out.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in merged), encoding="utf-8"
    )
    print(f"{len(merged)} rows into {out}")


if __name__ == "__main__":
    main()
