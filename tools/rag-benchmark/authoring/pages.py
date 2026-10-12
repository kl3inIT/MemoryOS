"""Question authoring aids (MEM-230 P4): page extraction from the originals and a seeded sample.

Not part of `memoryos_bench`, which stays standard-library only: this runs on an author's
workstation (`uvx --with pymupdf --with defusedxml python authoring/pages.py ...`). Questions are
written from these pages, never from MemoryOS's own chunks, so extraction and chunking stay part of
what the benchmark measures.

    pages.py extract   # page text per document; scanned or mixed pages also rendered to PNG
    pages.py sample    # the seeded draw of pages each department's questions are written from
"""

from __future__ import annotations

import argparse
import json
import random
import re
import zipfile
from pathlib import Path

import pymupdf
from defusedxml import ElementTree

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = json.loads((ROOT / "corpus" / "manifest.json").read_text(encoding="utf-8"))
# A page with less text than this is read from its image: scanned, or only a stamp and a signature.
TEXT_PAGE_CHARS = 200
# Rows of figures: a page with this share of number-bearing lines is a table page.
TABLE_LINE_SHARE = 0.35
NUMBER = re.compile(r"\d[\d.,]{3,}")


def docx_text(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        root = ElementTree.fromstring(archive.read("word/document.xml"))
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    paragraphs = [
        "".join(t.text or "" for t in p.iter(namespace + "t")) for p in root.iter(namespace + "p")
    ]
    return "\n".join(p for p in paragraphs if p.strip())


def kind_of(text: str) -> str:
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        return "image"
    share = sum(1 for line in lines if NUMBER.search(line)) / len(lines)
    return "table" if share >= TABLE_LINE_SHARE else "prose"


def extract(corpus: Path, out: Path) -> None:
    index = []
    for document in MANIFEST["documents"]:
        source = corpus / document["fileName"]
        target = out / document["id"]
        target.mkdir(parents=True, exist_ok=True)
        if document["format"] == "pdf":
            with pymupdf.open(source) as pdf:
                for number, page in enumerate(pdf, start=1):
                    text = page.get_text("text")
                    (target / f"{number:03d}.txt").write_text(text, encoding="utf-8")
                    image = len(text.strip()) < TEXT_PAGE_CHARS
                    if image:
                        page.get_pixmap(dpi=110).save(target / f"{number:03d}.png")
                    index.append(
                        {
                            "document": document["id"],
                            "page": number,
                            "chars": len(text.strip()),
                            "kind": "image" if image else kind_of(text),
                        }
                    )
        elif document["format"] == "docx":
            text = docx_text(source)
            (target / "001.txt").write_text(text, encoding="utf-8")
            index.append(
                {"document": document["id"], "page": 1, "chars": len(text), "kind": kind_of(text)}
            )
        else:
            # A .doc has no portable text extraction here; it is left to image-free review.
            index.append({"document": document["id"], "page": 1, "chars": 0, "kind": "unsupported"})
    (out / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"{len(index)} pages from {len(MANIFEST['documents'])} documents into {out}")


def sample(out: Path, seed: int, per_department: int) -> list[dict]:
    """Draws pages per department: one page of every document first, then the rest at random.

    Covering every document comes first so the short ones (each quarter's statements, each
    notice) can be asked about and compared across periods; the remaining draw is uniform over
    pages. In finance at least half of the pages are tables.
    """
    index = json.loads((out / "index.json").read_text(encoding="utf-8"))
    departments = {
        d["id"]: d["department"] for d in MANIFEST["documents"] if d["use"] == "question"
    }
    rng = random.Random(seed)  # noqa: S311 - a reproducible draw, not a secret
    drawn = []
    for department in sorted(set(departments.values())):
        pages = [
            p
            for p in index
            if departments.get(p["document"]) == department
            and p["kind"] != "unsupported"
            and (p["kind"] == "image" or p["chars"] >= TEXT_PAGE_CHARS)
        ]
        documents = sorted({p["document"] for p in pages})
        chosen = [rng.choice([p for p in pages if p["document"] == d]) for d in documents]
        rest = [p for p in pages if p not in chosen]
        if department == "finance":
            tables = sum(1 for p in chosen if p["kind"] == "table")
            needed = max(0, per_department // 2 - tables)
            table_rest = [p for p in rest if p["kind"] == "table"]
            extra = rng.sample(
                table_rest, min(needed, len(table_rest), max(0, per_department - len(chosen)))
            )
            chosen += extra
            rest = [p for p in rest if p not in extra]
        chosen += rng.sample(rest, min(max(0, per_department - len(chosen)), len(rest)))
        drawn += [
            {"department": department, **p}
            for p in sorted(chosen, key=lambda p: (p["document"], p["page"]))
        ]
    return drawn


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["extract", "sample"])
    parser.add_argument("--corpus", type=Path, default=Path.home() / ".memoryos-bench" / "corpus")
    parser.add_argument("--out", type=Path, default=Path.home() / ".memoryos-bench" / "pages")
    parser.add_argument("--seed", type=int, default=230)
    # Twice the twelve questions per department: a drawn page may hold nothing worth asking.
    parser.add_argument("--per-department", type=int, default=24)
    args = parser.parse_args()
    if args.command == "extract":
        extract(args.corpus, args.out)
    else:
        drawn = sample(args.out, args.seed, args.per_department)
        target = ROOT / "corpus" / "sample.json"
        target.write_text(
            json.dumps(
                {"seed": args.seed, "perDepartment": args.per_department, "pages": drawn},
                ensure_ascii=False,
                indent=1,
            )
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
        print(f"{len(drawn)} pages drawn with seed {args.seed} into {target}")


if __name__ == "__main__":
    main()
