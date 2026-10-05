"""Turns validated drafts into the question set: checks, derived expectations, split, review sample.

- Every quote must be on its page of the original (text pages; an image page's quote is checked by the owner's
  review and by the validators, since the page has no text layer).
- Question-to-quote token overlap above OVERLAP_LIMIT is reported for rewriting.
- Per-role expectations are derived from layout.json, never typed: a role that reads every evidence document
  answers, one that reads some answers partly, one that reads none declines; unreadable evidence documents are
  forbidden to it.
- dev/test split, 70/30, stratified by department and category, seeded.
- review.md: a seeded random 20% of the kept questions for the owner.

    python authoring/finalize.py validated.jsonl
"""

from __future__ import annotations

import argparse
import json
import random
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGES = Path.home() / ".memoryos-bench" / "pages"
MANIFEST = {
    d["id"]: d
    for d in json.loads((ROOT / "corpus" / "manifest.json").read_text("utf-8"))["documents"]
}
LAYOUT = json.loads((ROOT / "corpus" / "layout.json").read_text("utf-8"))
OVERLAP_LIMIT = 0.5
UNANSWERABLE = {"absent", "near_miss", "false_premise"}


def plain(text: str) -> str:
    text = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
    return "".join(c for c in text if unicodedata.category(c) != "Mn")


def tokens(text: str) -> list[str]:
    return re.findall(r"\w+", plain(text))


def overlap(question: str, quotes: list[str]) -> float:
    asked = [t for t in tokens(question) if len(t) > 2]
    quoted = set(t for q in quotes for t in tokens(q))
    return sum(t in quoted for t in asked) / len(asked) if asked else 0.0


def readers() -> dict[str, set[str]]:
    """Which documents each role reads, through its Groups and the Sources' Groups."""
    sources_of_group = defaultdict(set)
    for key, source in LAYOUT["sources"].items():
        for group in source["groups"]:
            sources_of_group[group].add(key)
    result = {}
    for role, groups in LAYOUT["roles"].items():
        sources = set().union(*(sources_of_group[g] for g in groups)) if groups else set()
        result[role] = {
            d
            for d, m in MANIFEST.items()
            if ("noise" if m["use"] == "noise" else m["department"]) in sources
        }
    return result


def expectations(question: dict, reads: dict[str, set[str]]) -> dict[str, dict]:
    """What each role's reply must do, from the evidence and the layout alone.

    `absent`: every role declines. `near_miss` and `false_premise`: a role that reads the evidence does what the
    gold answer says (name the right period, correct the premise, answer from the right company); a role that
    reads none of it declines. Answerable questions: every evidence document readable → answer, some → partial,
    none → abstain. The tempting wrong document is never to be cited.
    """
    evidence = {e["document"] for e in question["evidence"]}
    tempting = question.get("tempting_wrong_document")
    result = {}
    for role, readable in reads.items():
        seen = evidence & readable
        if question["category"] == "absent" or not evidence:
            behaviour = "abstain"
        elif question["category"] in UNANSWERABLE:
            behaviour = "follow_gold" if seen else "abstain"
        elif seen == evidence:
            behaviour = "answer"
        elif seen:
            behaviour = "partial"
        else:
            behaviour = "abstain"
        result[role] = {
            "expect": behaviour,
            "forbidden_documents": sorted(evidence - readable),
            "must_not_cite": [tempting] if tempting else [],
        }
    return result


def quote_on_page(evidence: dict) -> bool | None:
    page = PAGES / evidence["document"] / f"{evidence['page']:03d}.txt"
    text = page.read_text("utf-8") if page.exists() else ""
    if len(text.strip()) < 200:
        return None  # an image page: no text layer to check against
    squash = lambda s: re.sub(r"\s+", " ", s).strip()  # noqa: E731
    return squash(evidence["quote"]) in squash(text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("validated", type=Path)
    parser.add_argument("--seed", type=int, default=230)
    args = parser.parse_args()
    rng = random.Random(args.seed)  # noqa: S311 - a reproducible split, not a secret
    reads = readers()
    rows = [
        json.loads(line) for line in args.validated.read_text("utf-8").splitlines() if line.strip()
    ]
    problems, kept = [], []
    for row in rows:
        if not row.get("kept"):
            continue
        checks = [quote_on_page(e) for e in row["evidence"]]
        if False in checks:
            problems.append(f"{row['id']}: a quote is not on its page")
            continue
        ratio = overlap(row["question"], [e["quote"] for e in row["evidence"]])
        if ratio > OVERLAP_LIMIT and row["category"] not in UNANSWERABLE:
            problems.append(
                f"{row['id']}: overlap {ratio:.2f} with its quote; rewrite in user voice"
            )
            continue
        row["overlap"] = round(ratio, 2)
        row["quote_checked_on_text"] = all(c is True for c in checks)
        row["expectations"] = expectations(row, reads)
        kept.append(row)

    strata = defaultdict(list)
    for row in kept:
        strata[(row["department"], row["category"])].append(row)
    for group in strata.values():
        rng.shuffle(group)
        cut = round(len(group) * 0.3)
        for index, row in enumerate(group):
            row["split"] = "test" if index < cut else "dev"

    out = ROOT / "corpus" / "questions.jsonl"
    fields = (
        "id",
        "department",
        "category",
        "split",
        "question",
        "as_of",
        "evidence",
        "gold_answer",
        "passage_type",
        "tempting_wrong_document",
        "drafted_by",
        "validated_by",
        "overlap",
        "quote_checked_on_text",
        "expectations",
    )
    with out.open("w", encoding="utf-8", newline="\n") as handle:
        for row in sorted(kept, key=lambda r: r["id"]):
            handle.write(json.dumps({f: row.get(f) for f in fields}, ensure_ascii=False) + "\n")

    sample = rng.sample(kept, max(1, round(len(kept) * 0.2))) if kept else []
    lines = [
        "# Review sample",
        "",
        f"{len(sample)} of {len(kept)} kept questions, seed {args.seed}.",
        "",
    ]
    for row in sorted(sample, key=lambda r: r["id"]):
        roles = ", ".join(f"{r}: {e['expect']}" for r, e in row["expectations"].items())
        lines += [
            f"## {row['id']} ({row['category']}, {row['split']})",
            "",
            f"**Hỏi:** {row['question']}",
            "",
        ]
        lines += [
            f"> {e['quote']}  \n> — {e['document']}, trang {e['page']}" for e in row["evidence"]
        ]
        lines += ["", f"**Đáp án chuẩn:** {row['gold_answer']}", "", f"Vai trò: {roles}", ""]
    (ROOT / "corpus" / "review.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8", newline="\n"
    )

    print(
        f"{len(kept)} questions written, {len(rows) - sum(r.get('kept', False) for r in rows)} rejected "
        f"by validation, {len(problems)} sent back"
    )
    for line in problems:
        print("  " + line)


if __name__ == "__main__":
    main()
