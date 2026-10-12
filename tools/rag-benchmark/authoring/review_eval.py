"""The question set as a promptfoo eval for the owner's review: one row per question, no model called.

The `echo` provider returns the gold answer, so the viewer shows question, gold, evidence and per-role
expectations side by side, filterable by department, category and split. Ratings and comments made in the
viewer are the review.

    python authoring/review_eval.py corpus/questions.jsonl --out <dir>   # writes promptfooconfig.yaml
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("questions", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    tests = []
    for line in args.questions.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        q = json.loads(line)
        evidence = "\n\n".join(
            f"[{e['document']}, trang {e['page']}]\n{e['quote']}" for e in q["evidence"]
        )
        roles = ", ".join(f"{role}: {e['expect']}" for role, e in q["expectations"].items())
        tests.append(
            {
                "description": f"{q['id']} · {q['category']} · {q['split']}",
                "vars": {
                    "id": q["id"],
                    "question": q["question"],
                    "gold": q["gold_answer"],
                    "evidence": evidence or "(không có bằng chứng: phải từ chối)",
                    "roles": roles,
                    "as_of": q.get("as_of") or "",
                },
                "metadata": {
                    "department": q["department"],
                    "category": q["category"],
                    "split": q["split"],
                    "passage_type": q.get("passage_type") or "",
                },
            }
        )
    config = {
        "description": f"MEM-230 question review ({len(tests)} questions)",
        "prompts": ["{{gold}}"],
        "providers": ["echo"],
        "tests": tests,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    # JSON is valid YAML, and promptfoo reads it as such.
    (args.out / "promptfooconfig.yaml").write_text(
        json.dumps(config, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"{len(tests)} questions into {args.out / 'promptfooconfig.yaml'}")


if __name__ == "__main__":
    main()
