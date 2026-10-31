"""Builds the benchmark reference workbook from the repository: the corpus, the roles, the 120 questions and
their evidence, the method, every run's per-row results and the comparison tables with 95% intervals.

    uv run --with openpyxl python report/build_workbook.py <out.xlsx>

Everything comes from `corpus/` and `results/` (per-row results reduced from promptfoo exports by
`promptfoo/export_rows.py`); nothing is typed in by hand except the method text below. A rate in the summary
tables sits next to an AVERAGEIFS formula over the results sheet that recomputes it.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.worksheet import Worksheet

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "promptfoo"))

import summary  # noqa: E402

CORPUS = ROOT / "corpus"
RESULTS = ROOT / "results"
FONT = "Calibri"
HEADER_FILL = PatternFill("solid", fgColor="FBE4D5")
THIN = Side(style="thin", color="999999")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
ROLES = ["exec", "finance", "legal", "governance", "ir", "hr", "outsider"]
CHAT_RUNS = [
    "baseline-v3-standard",
    "baseline-v3-grounded",
    "ablation-v0",
    "ablation-v1a",
    "ablation-v1b",
    "ablation-v6",
]
RETRIEVAL_RUNS = ["retrieval-hybrid", "retrieval-keyword", "retrieval-vector"]
CHAT_METRICS = [
    "leakage",
    "tempting",
    "citation",
    "correctness",
    "faithfulness",
    "behaviour",
    "context_recall",
    "context_precision",
    "completeness",
    "correctness_sol",
    "completeness_sol",
]
RETRIEVAL_METRICS = [
    "extraction_coverage",
    "recall_at_10",
    "recall_at_50",
    "ndcg_at_10",
    "mrr_at_10",
    "passage_recall_at_10",
    "tempting_first",
]

METRICS = [
    # metric, stage, what it measures, how it is computed, example, scored by
    (
        "extraction_coverage",
        "1 Extraction",
        "Whether the evidence sentence is in the text MemoryOS indexed for its document",
        "Share of a question's evidence quotes found in the document's indexed text. A quote is found when at least 90% of its 6-character pieces occur, counted per fragment and line, after removing spaces and punctuation",
        "The quote 'Tỷ lệ chi trả: 05%' is found when the index holds 'Tỷ lệ chi trả: 05 %'; a date line OCR dropped is not found",
        "Program code",
    ),
    (
        "recall_at_10",
        "2 Search",
        "Whether search returns a right document among the first 10 results",
        "Gold documents in the first 10 results divided by all gold documents of the question; averaged over questions",
        "Gold documents A and B; A is 3rd and B is 14th: 1 of 2 = 50%",
        "Program code",
    ),
    (
        "recall_at_50",
        "2 Search",
        "As recall@10, to 50 results",
        "As recall@10 with 50 results. With 61 documents it covers nearly the whole corpus",
        "",
        "Program code",
    ),
    (
        "ndcg_at_10",
        "2 Search",
        "How high the gold documents are placed in the first 10 results",
        "DCG = sum of 1/log2(rank+1) over gold documents in the first 10; divided by the best possible DCG for that many gold documents",
        "One gold document at rank 2: 1/log2(3) = 0.63",
        "Program code",
    ),
    (
        "mrr_at_10",
        "2 Search",
        "Rank of the first gold document",
        "1 / rank of the first gold document in the first 10, else 0",
        "First gold document at rank 3: 0.33",
        "Program code",
    ),
    (
        "passage_recall_at_10",
        "2 Search",
        "Whether the returned sections hold the evidence, not only the right document",
        "Share of evidence quotes found in the sections returned with the first 10 documents",
        "",
        "Program code",
    ),
    (
        "tempting_first",
        "2 Search",
        "Whether the tempting wrong document (another period or company) outranks every gold one",
        "1 when it ranks above all gold documents, else 0; only for questions that name a tempting document",
        "",
        "Program code",
    ),
    (
        "context_recall",
        "3 Chat reading",
        "Whether the model read the gold documents",
        "Gold documents the role may read that are among the documents given to the model, divided by those gold documents",
        "Needs two documents, read one: 50%",
        "Program code",
    ),
    (
        "context_precision",
        "3 Chat reading",
        "How much of what the model read is gold",
        "Gold documents read divided by all documents read; a correct second source lowers it, so it is a lower bound",
        "Read 4, 2 are gold: 50%",
        "Program code",
    ),
    (
        "correctness",
        "4 Answer",
        "The answer agrees with the gold answer",
        "promptfoo factuality: the judge compares the answer with the gold answer; fails on a conflicting fact",
        "",
        "Judge cx/gpt-6-luna",
    ),
    (
        "correctness_sol",
        "4 Answer",
        "Correctness by a second judge",
        "Same check by cx/gpt-6-sol, to test whether the Luna judge favours Luna's replies",
        "",
        "Judge cx/gpt-6-sol",
    ),
    (
        "completeness",
        "4 Answer",
        "The answer gives every point the question asks for",
        "llm-rubric with the question and the answer: every quantity, person, date, place or choice asked for must match the gold answer; details the question does not ask for are not required",
        "Asked amount and date, answer gives only the amount: fail",
        "Judge cx/gpt-6-luna",
    ),
    (
        "completeness_sol",
        "4 Answer",
        "Completeness by a second judge",
        "Same rubric by cx/gpt-6-sol",
        "",
        "Judge cx/gpt-6-sol",
    ),
    (
        "faithfulness",
        "4 Answer",
        "Every fact in the answer is supported by the cited text",
        "llm-rubric over the question, the answer and the text of the cited passages",
        "",
        "Judge cx/gpt-6-luna",
    ),
    (
        "behaviour",
        "4 Answer",
        "A decline, partial or tricky question is handled as its expectation says",
        "llm-rubric per expectation: decline when nothing answers, answer only from readable documents, correct a false premise, name the right period",
        "",
        "Judge cx/gpt-6-luna",
    ),
    (
        "citation",
        "4 Answer",
        "Every [n] in the answer names one of its sources",
        "Program code over the reply's citations",
        "",
        "Program code",
    ),
    (
        "leakage",
        "Security",
        "A document the role may not read was read or cited",
        "Program code: forbidden documents of the role against documents read and cited; must be 0",
        "",
        "Program code",
    ),
    (
        "tempting",
        "4 Answer",
        "The tempting wrong document was cited",
        "Program code: the question's tempting document against the cited documents",
        "",
        "Program code",
    ),
    (
        "pass_all",
        "Row",
        "The row passed every check of its run",
        "All assertions of the row passed",
        "",
        "promptfoo",
    ),
    (
        "95% interval",
        "Statistics",
        "The range where the true value most likely lies",
        "Bootstrap: draw the questions again at random 10,000 times (seed 230), recompute, take the 2.5% and 97.5% points. Questions, not rows, are drawn, because one question is asked by several roles",
        "82.4% [75.6, 88.6]",
        "Statistics",
    ),
    (
        "paired difference",
        "Statistics",
        "Whether one version beats another",
        "Per row, version A minus version B on the same question and role; bootstrap interval of the mean difference. An interval containing zero is no measured difference",
        "+7.0 [1.9, 12.1] proven; +4.1 [-0.5, 8.9] not proven",
        "Statistics",
    ),
]

METHOD = [
    (
        "1 Corpus",
        "61 public disclosures collected 2026-10-05 from savico.com.vn, haxaco.com.vn and two CafeF mirrors: 49 Savico (SVC) documents for questions (finance 14, governance 13, HR 13, investor relations 3, legal 6) and 12 Haxaco (HAX) documents of the same sector as noise that is never questioned. Text layer: 25 text, 10 mixed, 26 scanned. Each file is kept by URL and SHA-256; the provisioning script downloads it and refuses a changed file. Real conflicts were kept on purpose (superseded charters and notices, overlapping governance reports).",
        "Corpus",
    ),
    (
        "2 Access",
        "Six Sources (one per department plus Haxaco) and six Groups in the staging tenant; seven roles (exec reads all, each department role its own Savico Source and Haxaco, outsider nothing). Each role is a test account placed only in its benchmark Groups.",
        "Roles",
    ),
    (
        "3 Pages and sample",
        "1,750 pages extracted from the originals (1,075 read from images, 462 prose, 213 tables). 24 pages per department drawn with seed 230, one page of every question document first; questions start from a drawn page so the facts asked are not chosen for being easy.",
        "Evidence (page of each quote)",
    ),
    (
        "4 Drafting",
        "Six agents drafted from the brief: 17 questions per department and 40 across departments, ambiguous, general knowledge and sensitive, 125 in all. Questions are written in user voice (abbreviations, some without diacritics, some typos), never copying 4+ words from the quote; word-trigram overlap with the quotes is at most 0.41. Evidence is quoted verbatim from the original page, never from MemoryOS's own text, so extraction errors are measured, not hidden.",
        "Questions; Evidence",
    ),
    (
        "5 Bias controls",
        "Quotas fixed before reading; seeded page draw; three kinds of unanswerable question (absent, near miss, false premise); as_of on questions about current state; numeric gold checked on the page image for scanned pages; expectations per role derived from the access layout, not typed; 70/30 dev/test split, decisions read from test.",
        "Questions (category, split, as_of and expect columns)",
    ),
    (
        "6 Blind validation",
        "Two models of another family than the author (cx/gpt-6-luna and cx/gpt-6-sol) each answered every question from its quotes and from the whole document, without the gold answer; both had to reproduce it. 111 of 125 passed at first; 12 were rewritten once; 5 still rejected were dropped. 120 questions remain.",
        "Questions (validated_by)",
    ),
    (
        "7 Review",
        "A seeded 24-question sample (20%) was read against the original pages; no gold answer was wrong; two defects of the derived expectations were fixed for the whole set. The set was frozen at 120 on 2026-10-06.",
        "Questions",
    ),
    (
        "8 Who asks",
        "Each department question is asked by its own role, by exec and by one other role that must decline; cross-department, near-miss and false-premise questions by every role; ambiguous, general-knowledge and sensitive questions by exec. 375 rows in grounded mode, 361 in standard mode (general knowledge and sensitive run grounded only).",
        "Questions (expect columns); Results Chat",
    ),
    (
        "9 Runs",
        "promptfoo on the staging server, a fresh chat session per row, 2 concurrent turns, chat model cx/gpt-6-luna. A run refuses to start unless all 61 documents are indexed and searchable. Error rows are asked once more and merged.",
        "Runs",
    ),
    (
        "10 Retrieval-only runs",
        "Each of the 97 questions with a gold document for exec is searched once through POST /api/search as exec, with no Chat and no query rewrite. Keyword-only and vector-only ran on a test image whose hybrid query keeps one side, with the same filters, depth and permission checks; it was never merged.",
        "Results Retrieval; Summary",
    ),
    (
        "11 Stage re-grade",
        "The stored replies of the 2026-10-08 baseline were scored again without new turns: what the model read, completeness, and correctness and completeness by a second judge. The completeness rubric was calibrated on 20 hand-read replies: a first rubric (every point of the gold answer) failed 13 of 20 complete replies; the rubric used agreed with the hand reading on 20 of 20 for both judges.",
        "Results Chat; Summary (agreement between the two judges)",
    ),
    (
        "12 Ablation",
        "A step is tested by a test image that skips it (V1 no query rewrite, twice; V6 no adjacent chunks) against the released pipeline V0 on the same 375 rows the same day. A step is dropped only when removing it costs no more than noise on both splits, no category falls by more than 10 points and no leak appears.",
        "Summary (ablation runs, paired differences, rows passing by category)",
    ),
    (
        "13 Statistics",
        "Rates with 95% bootstrap intervals over questions (10,000 draws, seed 230); paired differences on the same rows. Re-grading unchanged replies moves a single check by up to 5 rows, so smaller differences are judge noise.",
        "Summary; Metrics",
    ),
]


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def style_header(ws: Worksheet, row: int, width: int) -> None:
    for col in range(1, width + 1):
        cell = ws.cell(row=row, column=col)
        cell.font = Font(name=FONT, size=11, bold=True)
        cell.fill = HEADER_FILL
        cell.border = BORDER
        cell.alignment = Alignment(wrap_text=True, vertical="top")


def write_table(
    ws: Worksheet,
    start_row: int,
    header: list[str],
    rows: list[list],
    widths: list[int] | None = None,
) -> int:
    for col, value in enumerate(header, 1):
        ws.cell(row=start_row, column=col, value=value)
    style_header(ws, start_row, len(header))
    for r, values in enumerate(rows, start_row + 1):
        for col, value in enumerate(values, 1):
            cell = ws.cell(row=r, column=col, value=value)
            cell.font = Font(name=FONT, size=11)
            cell.border = BORDER
            cell.alignment = Alignment(wrap_text=True, vertical="top")
    if widths:
        for col, width in enumerate(widths, 1):
            ws.column_dimensions[get_column_letter(col)].width = width
    return start_row + len(rows) + 1


def title(ws: Worksheet, row: int, text: str, note: str = "") -> int:
    ws.cell(row=row, column=1, value=text).font = Font(name=FONT, size=13, bold=True)
    if note:
        cell = ws.cell(row=row + 1, column=1, value=note)
        cell.font = Font(name=FONT, size=11, italic=True)
        return row + 2
    return row + 1


def interval(clusters: dict[str, list[float]]) -> tuple[float | None, float | None, float | None]:
    return summary.bootstrap(clusters, summary.mean)


def by_question(rows: list[dict], value) -> dict[str, list[float]]:  # noqa: ANN001
    clusters: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        v = value(row)
        if v is not None:
            clusters[row["id"]].append(v)
    return clusters


def score(row: dict, metric: str) -> float | None:
    if metric == "pass_all":
        return None if row["passAll"] is None else float(row["passAll"])
    entry = row["metrics"].get(metric)
    return None if entry is None else float(entry["score"])


def pct(value: float | None) -> float | None:
    return None if value is None else round(value, 4)


def main() -> None:
    out = Path(sys.argv[1])
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    layout = json.loads((CORPUS / "layout.json").read_text(encoding="utf-8"))
    questions = read_jsonl(CORPUS / "questions.jsonl")
    runs_meta = json.loads((RESULTS / "runs.json").read_text(encoding="utf-8"))
    label = {r["file"].removesuffix(".jsonl"): r["label"] for r in runs_meta["runs"]}
    runs = {name: read_jsonl(RESULTS / f"{name}.jsonl") for name in CHAT_RUNS + RETRIEVAL_RUNS}
    layers = summary.text_layers()

    wb = Workbook()

    # README
    ws = wb.active
    ws.title = "README"
    readme = [
        ("MemoryOS RAG benchmark: reference workbook", ""),
        (
            "Purpose",
            "Everything behind the measured quality in Report 4 (IV.1.8): the documents, the questions and their evidence, the method, every run's per-row results and the comparison tables with 95% intervals.",
        ),
        (
            "Data",
            "61 public disclosures of two listed companies (Savico, Haxaco), downloaded from their publishers. Demo accounts on the staging server; no customer data.",
        ),
        (
            "Generated",
            "Built automatically from the recorded questions and run results; nothing is typed in by hand. Every rate in Summary can be recalculated from the Results sheets.",
        ),
        ("Corpus fingerprint", runs_meta["corpusFingerprint"]),
        ("Chat model", runs_meta["chatModel"]),
        (
            "Judges",
            f"reference {runs_meta['judges']['reference']}, second {runs_meta['judges']['second']}",
        ),
        (
            "Sheets",
            "Summary: the comparison tables. Method: how the benchmark was built and run. Metrics: what each measure means, how it is computed, with examples. Corpus, Roles, Questions, Evidence: the test set. Runs: every run with its evals. Results Chat and Results Retrieval: one row per question and role per run.",
        ),
        (
            "Reading a rate",
            "82.4% [75.6, 88.6] means the measured value is 82.4% and the true value very likely lies between 75.6% and 88.6%. A difference whose interval contains zero is no measured difference. The 'check' column recomputes the rate from the results sheet with AVERAGEIFS.",
        ),
    ]
    for r, (key, value) in enumerate(readme, 1):
        ws.cell(row=r, column=1, value=key).font = Font(
            name=FONT, size=13 if r == 1 else 11, bold=True
        )
        cell = ws.cell(row=r, column=2, value=value)
        cell.font = Font(name=FONT, size=11)
        cell.alignment = Alignment(wrap_text=True, vertical="top")
    ws.column_dimensions["A"].width = 22
    ws.column_dimensions["B"].width = 110

    # Method
    ws = wb.create_sheet("Method")
    write_table(
        ws,
        1,
        ["Step", "What was done", "Where to see it in this workbook"],
        [list(m) for m in METHOD],
        [22, 110, 38],
    )
    ws.freeze_panes = "A2"

    # Metrics
    ws = wb.create_sheet("Metrics")
    write_table(
        ws,
        1,
        ["Metric", "Stage", "What it measures", "How it is computed", "Example", "Scored by"],
        [list(m) for m in METRICS],
        [22, 15, 40, 60, 40, 18],
    )
    ws.freeze_panes = "A2"

    # Corpus
    ws = wb.create_sheet("Corpus")
    cols = [
        "id",
        "company",
        "use",
        "department",
        "period",
        "kind",
        "format",
        "textLayer",
        "bytes",
        "url",
        "sha256",
    ]
    write_table(
        ws,
        1,
        cols,
        [[d.get(c) for c in cols] for d in manifest["documents"]],
        [30, 9, 9, 18, 12, 30, 7, 10, 11, 60, 66],
    )
    ws.freeze_panes = "B2"
    ws.auto_filter.ref = ws.dimensions

    # Roles
    ws = wb.create_sheet("Roles")
    source_groups = {key: spec for key, spec in layout["sources"].items()}
    rows = []
    for role, groups in layout["roles"].items():
        readable = [
            spec["name"] for spec in source_groups.values() if set(spec["groups"]) & set(groups)
        ]
        rows.append(
            [
                role,
                ", ".join(layout["groups"][g] for g in groups) or "(none)",
                "; ".join(readable) or "(none)",
            ]
        )
    write_table(ws, 1, ["Role", "Groups", "Sources it can read"], rows, [14, 40, 110])

    # Questions
    ws = wb.create_sheet("Questions")
    cols = [
        "id",
        "department",
        "category",
        "split",
        "question",
        "as_of",
        "gold_answer",
        "passage_type",
        "tempting_wrong_document",
        "evidence documents",
        "text layer",
        "validated_by",
    ] + [f"expect {r}" for r in ROLES]
    rows = []
    for q in questions:
        rows.append(
            [
                q["id"],
                q["department"],
                q["category"],
                q["split"],
                q["question"],
                q.get("as_of"),
                q["gold_answer"],
                q.get("passage_type"),
                q.get("tempting_wrong_document"),
                ", ".join(sorted({e["document"] for e in q.get("evidence") or []})),
                layers.get(q["id"], ""),
                ", ".join(q.get("validated_by") or []),
            ]
            + [(q["expectations"].get(r) or {}).get("expect", "") for r in ROLES]
        )
    write_table(
        ws, 1, cols, rows, [16, 14, 16, 7, 60, 11, 70, 13, 26, 36, 10, 26] + [10] * len(ROLES)
    )
    ws.freeze_panes = "B2"
    ws.auto_filter.ref = ws.dimensions

    # Evidence
    ws = wb.create_sheet("Evidence")
    rows = [
        [q["id"], e["document"], e.get("page"), e["quote"]]
        for q in questions
        for e in q.get("evidence") or []
    ]
    write_table(
        ws,
        1,
        ["question", "document", "page", "quote (verbatim from the original page)"],
        rows,
        [16, 30, 7, 120],
    )
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions

    # Runs
    ws = wb.create_sheet("Runs")
    rows = []
    for meta in runs_meta["runs"]:
        name = meta["file"].removesuffix(".jsonl")
        data = runs[name]
        rows.append(
            [
                name,
                meta["label"],
                meta["date"],
                meta["api"],
                "; ".join(meta["evals"]),
                len(data),
                sum(1 for r in data if r["error"]),
            ]
        )
    write_table(
        ws,
        1,
        ["Run", "What", "Date", "API build", "promptfoo evals", "Rows", "Rows still in error"],
        rows,
        [24, 40, 11, 60, 70, 7, 10],
    )

    # Results (chat)
    ws = wb.create_sheet("Results Chat")
    cols = (
        ["run", "id", "role", "mode", "category", "split", "expect", "text layer", "pass_all"]
        + CHAT_METRICS
        + ["seconds", "read documents", "cited documents", "error", "answer"]
    )
    rows = []
    for name in CHAT_RUNS:
        for r in runs[name]:
            rows.append(
                [
                    name,
                    r["id"],
                    r["role"],
                    r["mode"],
                    r["category"],
                    r["split"],
                    r["expect"],
                    layers.get(r["id"], ""),
                    score(r, "pass_all"),
                ]
                + [score(r, m) for m in CHAT_METRICS]
                + [
                    r["seconds"],
                    ", ".join(r["readDocuments"]),
                    ", ".join(r["citedDocuments"]),
                    r["error"],
                    r["answer"],
                ]
            )
    write_table(
        ws,
        1,
        cols,
        rows,
        [20, 16, 10, 10, 16, 6, 11, 9, 8] + [9] * len(CHAT_METRICS) + [8, 40, 40, 20, 80],
    )
    ws.freeze_panes = "C2"
    ws.auto_filter.ref = ws.dimensions
    chat_col = {c: get_column_letter(i) for i, c in enumerate(cols, 1)}
    chat_last = len(rows) + 1

    # Results (retrieval)
    ws = wb.create_sheet("Results Retrieval")
    cols = (
        ["run", "id", "category", "split", "text layer"]
        + RETRIEVAL_METRICS
        + ["first 10 documents", "error"]
    )
    rows = []
    for name in RETRIEVAL_RUNS:
        for r in runs[name]:
            rows.append(
                [name, r["id"], r["category"], r["split"], layers.get(r["id"], "")]
                + [score(r, m) for m in RETRIEVAL_METRICS]
                + [", ".join(r["top10"]), r["error"]]
            )
    write_table(ws, 1, cols, rows, [18, 16, 16, 6, 9] + [11] * len(RETRIEVAL_METRICS) + [80, 20])
    ws.freeze_panes = "C2"
    ws.auto_filter.ref = ws.dimensions
    ret_col = {c: get_column_letter(i) for i, c in enumerate(cols, 1)}
    ret_last = len(rows) + 1

    # Summary
    ws = wb.create_sheet("Summary", 1)

    def check(
        sheet: str,
        cols_: dict,
        last: int,
        run: str,
        metric: str,
        extra: tuple[str, str] | None = None,
    ) -> str:
        crit = f'{sheet}!${cols_["run"]}$2:${cols_["run"]}${last},"{run}"'
        if extra:
            crit += f',{sheet}!${cols_[extra[0]]}$2:${cols_[extra[0]]}${last},"{extra[1]}"'
        rng = f"{sheet}!${cols_[metric]}$2:${cols_[metric]}${last}"
        return f'=IFERROR(AVERAGEIFS({rng},{crit}),"")'

    def rate_row(
        rows_: list[dict],
        metric: str,
        sheet: str,
        cols_: dict,
        last: int,
        run: str,
        name: str,
        extra: tuple[str, str] | None = None,
    ) -> list:
        point, low, high = interval(by_question(rows_, lambda r: score(r, metric)))
        n = sum(1 for r in rows_ if score(r, metric) is not None)
        return [
            name,
            pct(point),
            pct(low),
            pct(high),
            n,
            check(sheet, cols_, last, run, metric, extra),
        ]

    row = title(
        ws,
        1,
        "Summary tables",
        "Rates are fractions shown as percentages. Low and high are the 95% bootstrap interval. 'Check' recomputes the rate from the results sheet.",
    )
    head = ["Measure", "Rate", "Low", "High", "Rows", "Check"]
    percent_rows: list[tuple[int, int]] = []

    def block(heading: str, note: str, lines: list[list]) -> None:
        nonlocal row
        row = title(ws, row + 1, heading, note)
        start = row
        row = write_table(ws, row, head, lines)
        percent_rows.append((start + 1, row - 1))

    sheet_chat, sheet_ret = "'Results Chat'", "'Results Retrieval'"
    for mode, name in (("standard", "baseline-v3-standard"), ("grounded", "baseline-v3-grounded")):
        lines = [
            rate_row(runs[name], m, sheet_chat, chat_col, chat_last, name, m)
            for m in ["pass_all"] + CHAT_METRICS
            if any(score(r, m) is not None for r in runs[name])
        ]
        block(
            f"Baseline v3, {mode} mode (2026-10-08 replies, stage metrics re-graded 2026-10-31)",
            label[name],
            lines,
        )
    for layer in ("text", "mixed", "scanned"):
        name = "baseline-v3-grounded"
        subset = [r for r in runs[name] if layers.get(r["id"]) == layer]
        lines = [
            rate_row(subset, m, sheet_chat, chat_col, chat_last, name, m, ("text layer", layer))
            for m in ["context_recall", "correctness", "completeness"]
        ]
        block(
            f"Grounded baseline, questions whose gold documents are {layer}",
            "Text layer of the evidence documents: text, scanned, or a mix.",
            lines,
        )
    for name in RETRIEVAL_RUNS:
        lines = [
            rate_row(runs[name], m, sheet_ret, ret_col, ret_last, name, m)
            for m in RETRIEVAL_METRICS
        ]
        block(
            label[name],
            "97 questions with a gold document for exec; one search each, no Chat.",
            lines,
        )
    for name in ("ablation-v0", "ablation-v1a", "ablation-v1b", "ablation-v6"):
        lines = [
            rate_row(runs[name], m, sheet_chat, chat_col, chat_last, name, m)
            for m in ["pass_all", "correctness", "faithfulness", "behaviour", "leakage", "citation"]
        ]
        block(label[name], "375 grounded rows, error rows asked once more.", lines)

    # Paired differences
    row = title(
        ws,
        row + 1,
        "Paired differences",
        "Version minus reference on the same rows; low and high are the 95% interval; an interval containing zero is no measured difference.",
    )
    pairs = [
        (
            "retrieval-hybrid",
            "retrieval-keyword",
            ["recall_at_10", "ndcg_at_10", "mrr_at_10", "passage_recall_at_10"],
        ),
        (
            "retrieval-hybrid",
            "retrieval-vector",
            ["recall_at_10", "ndcg_at_10", "mrr_at_10", "passage_recall_at_10"],
        ),
        ("ablation-v1a", "ablation-v0", ["pass_all", "correctness", "behaviour"]),
        ("ablation-v1b", "ablation-v0", ["pass_all", "correctness", "behaviour"]),
        ("ablation-v6", "ablation-v0", ["pass_all", "correctness", "behaviour"]),
    ]
    lines = []
    for a, b, metrics in pairs:
        other = {(r["id"], r["role"]): r for r in runs[b]}
        for m in metrics:
            clusters: dict[str, list[float]] = defaultdict(list)
            for r in runs[a]:
                twin = other.get((r["id"], r["role"]))
                if twin and score(r, m) is not None and score(twin, m) is not None:
                    clusters[r["id"]].append(score(r, m) - score(twin, m))
            point, low, high = interval(clusters)
            lines.append(
                [
                    f"{a} minus {b}",
                    m,
                    pct(point),
                    pct(low),
                    pct(high),
                    sum(len(v) for v in clusters.values()),
                ]
            )
    start = row
    row = write_table(
        ws, row, ["Comparison", "Measure", "Difference", "Low", "High", "Rows"], lines
    )
    for r in range(start + 1, row):
        for c in (3, 4, 5):
            ws.cell(row=r, column=c).number_format = "+0.0%;-0.0%;0.0%"

    # Ablation by category
    row = title(
        ws,
        row + 1,
        "Rows passing by category, ablation runs",
        "Share of rows passing every check; category sizes are small, so read single cells with care.",
    )
    cats = sorted({r["category"] for r in runs["ablation-v0"]})
    lines = []
    for name in ("ablation-v0", "ablation-v1a", "ablation-v1b", "ablation-v6"):
        line = [name]
        for c in cats:
            values = [
                score(r, "pass_all")
                for r in runs[name]
                if r["category"] == c and score(r, "pass_all") is not None
            ]
            line.append(round(sum(values) / len(values), 4) if values else None)
        lines.append(line)
    start = row
    row = write_table(ws, row, ["Run"] + cats, lines)
    for r in range(start + 1, row):
        for c in range(2, len(cats) + 2):
            ws.cell(row=r, column=c).number_format = "0.0%"

    # Judge agreement
    row = title(
        ws,
        row + 1,
        "Agreement between the two judges",
        "Same rows graded by cx/gpt-6-luna and cx/gpt-6-sol.",
    )
    lines = []
    for name in ("baseline-v3-standard", "baseline-v3-grounded"):
        for first, second in (
            ("correctness", "correctness_sol"),
            ("completeness", "completeness_sol"),
        ):
            pairs_ = [
                (r["metrics"][first]["pass"], r["metrics"][second]["pass"])
                for r in runs[name]
                if first in r["metrics"] and second in r["metrics"]
            ]
            observed, k = summary.kappa(pairs_)
            lines.append(
                [name, f"{first} vs {second}", round(observed, 4), round(k, 2), len(pairs_)]
            )
    start = row
    row = write_table(ws, row, ["Run", "Measure", "Agreement", "Cohen's kappa", "Rows"], lines)
    for r in range(start + 1, row):
        ws.cell(row=r, column=3).number_format = "0.0%"

    for first, last in percent_rows:
        for r in range(first, last + 1):
            for c in (2, 3, 4, 6):
                ws.cell(row=r, column=c).number_format = "0.0%"
    for col, width in zip("ABCDEF", (46, 24, 12, 12, 12, 12), strict=True):
        ws.column_dimensions[col].width = width

    for sheet in wb.worksheets:
        for cells in sheet.iter_rows():
            for cell in cells:
                if cell.font.name != FONT:
                    cell.font = Font(
                        name=FONT,
                        size=cell.font.size or 11,
                        bold=cell.font.bold,
                        italic=cell.font.italic,
                    )
    out.parent.mkdir(parents=True, exist_ok=True)
    wb.save(out)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
