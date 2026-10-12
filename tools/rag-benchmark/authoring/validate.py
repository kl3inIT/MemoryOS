"""Blind validation of drafted questions by two models of another family than the author.

Each validator answers twice without seeing the gold answer:

- **passage**: the question and its evidence quotes only; the reply must reach the gold answer.
- **document**: the question and the full text of each evidence document, without the quotes; the reply must reach
  the same answer, which shows the question has one right answer in its documents. A scanned document has no text
  layer here, so for it this pass is skipped and recorded as such.

A third call per pass asks the same validator whether its blind reply agrees with the gold answer. A question is
kept only when every pass of every validator agrees. Standard library only.

    MEMORYOS_JUDGE_API_KEY=... python authoring/validate.py drafts/*.jsonl --out validated.jsonl
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE_URL = os.environ.get("MEMORYOS_JUDGE_BASE_URL", "https://9router.zeromail.vn/v1")
VALIDATORS = ("cx/gpt-6-luna", "cx/gpt-6-sol")
PAGES = Path.home() / ".memoryos-bench" / "pages"
# Enough for an annual report's text layer; a longer document is cut and the cut is recorded.
DOCUMENT_CHARS = 400_000

ANSWER = (
    "Bạn trả lời câu hỏi của nhân viên chỉ dựa trên tài liệu được cung cấp, không dùng hiểu biết riêng. "
    "Nếu tài liệu không đủ để trả lời, nói rõ là không trả lời được và vì sao. Nếu câu hỏi dựa trên một tiền đề "
    "mà tài liệu cho thấy là sai, chỉ ra điều đó. Trả lời ngắn gọn, giữ nguyên số liệu như trong tài liệu."
)
AGREE = (
    'So sánh một câu trả lời với đáp án chuẩn của cùng câu hỏi. Trả về JSON {"agrees": true|false, '
    '"reason": "..."}. agrees = true khi câu trả lời đưa ra cùng dữ kiện cốt lõi (cùng số liệu, cùng người, '
    "cùng kỳ) hoặc, với đáp án yêu cầu từ chối hay sửa tiền đề, khi câu trả lời cũng từ chối hay sửa tiền đề đó. "
    "Khác cách diễn đạt hay thừa chi tiết đúng không làm sai. Khi đáp án chuẩn yêu cầu từ chối, câu trả lời "
    "đạt nếu không đưa ra thông tin hay quan điểm được hỏi, dù lý do từ chối nó nêu khác với đáp án."
)


def chat(model: str, system: str, user: str, key: str) -> str:
    body = {
        "model": model,
        "stream": False,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }
    request = urllib.request.Request(  # noqa: S310 - the configured endpoint
        BASE_URL.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    )
    for attempt in range(6):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:  # noqa: S310
                return json.loads(response.read())["choices"][0]["message"]["content"] or ""
        except (urllib.error.URLError, TimeoutError, KeyError) as error:
            if attempt == 5:
                raise
            # The Codex accounts behind 9Router lock for about a minute and a half on a rate limit, and
            # other services share them: wait the lock out instead of hammering it.
            limited = isinstance(error, urllib.error.HTTPError) and error.code in (429, 503)
            time.sleep(100 if limited else 5 * (attempt + 1))
    raise AssertionError("unreachable")


MANIFEST = {
    d["id"]: d
    for d in json.loads(
        (Path(__file__).resolve().parent.parent / "corpus" / "manifest.json").read_text("utf-8")
    )["documents"]
}
COMPANY = {"svc": "Savico", "hax": "Haxaco"}


def label(document: str) -> str:
    """What a reader sees of a document beside a passage: company, kind and period, as a title would say."""
    meta = MANIFEST[document]
    return f"{COMPANY[meta['company']]} — {meta['kind']}, {meta['period']}"


def asked_on(question: dict) -> str:
    """The date the question is asked on, which a chat system always knows."""
    return f"Hôm nay là ngày {question['as_of']}.\n\n" if question.get("as_of") else ""


def document_text(document: str) -> str:
    pages = sorted((PAGES / document).glob("*.txt"))
    text = "\n".join(f"[trang {int(p.stem)}]\n{p.read_text(encoding='utf-8')}" for p in pages)
    return text[:DOCUMENT_CHARS]


def has_text(document: str, page: int) -> bool:
    path = PAGES / document / f"{page:03d}.txt"
    return path.exists() and len(path.read_text(encoding="utf-8").strip()) >= 200


def digest(question: dict) -> str:
    """Names a draft's content, so an edited draft is validated again."""
    fields = {
        k: question.get(k) for k in ("question", "as_of", "evidence", "gold_answer", "category")
    }
    return hashlib.sha256(
        json.dumps(fields, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def agrees(model: str, question: dict, reply: str, key: str) -> tuple[bool, str]:
    raw = chat(
        model,
        AGREE,
        json.dumps(
            {
                "question": question["question"],
                "gold_answer": question["gold_answer"],
                "answer": reply,
            },
            ensure_ascii=False,
        ),
        key,
    )
    start, end = raw.find("{"), raw.rfind("}")
    try:
        verdict = json.loads(raw[start : end + 1])
        return bool(verdict.get("agrees")), str(verdict.get("reason", ""))
    except ValueError:
        return False, "unparseable verdict: " + raw[:200]


def validate(question: dict, key: str) -> dict:
    passages = "\n\n".join(
        f"[{label(e['document'])}, trang {e['page']}]\n{e['quote']}" for e in question["evidence"]
    )
    documents, skipped = [], []
    for document in sorted({e["document"] for e in question["evidence"]}):
        # The document pass needs the evidence inside the text it is given: a scanned evidence page has
        # no text layer, and without it the pass would reject a sound question.
        if all(
            has_text(document, e["page"]) for e in question["evidence"] if e["document"] == document
        ):
            documents.append(f"=== {label(document)} ===\n{document_text(document)}")
        else:
            skipped.append(document)
    if skipped:
        documents = []  # a partial document set would make the uniqueness pass meaningless
    results = {}
    for model in VALIDATORS:
        passes = {}
        reply = chat(
            model,
            ANSWER,
            f"{asked_on(question)}Tài liệu:\n{passages}\n\nCâu hỏi: {question['question']}",
            key,
        )
        passes["passage"] = {
            "reply": reply,
            **dict(zip(("agrees", "reason"), agrees(model, question, reply, key), strict=True)),
        }
        if documents:
            reply = chat(
                model,
                ANSWER,
                asked_on(question)
                + "Tài liệu:\n"
                + "\n\n".join(documents)
                + f"\n\nCâu hỏi: {question['question']}",
                key,
            )
            passes["document"] = {
                "reply": reply,
                **dict(zip(("agrees", "reason"), agrees(model, question, reply, key), strict=True)),
            }
        results[model] = passes
    kept = all(p["agrees"] for passes in results.values() for p in passes.values())
    return {
        **question,
        "digest": digest(question),
        "validated_by": list(VALIDATORS),
        "validation": results,
        "document_pass_skipped": skipped,
        "kept": kept,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("drafts", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    # Two at a time: the Codex accounts behind 9Router are shared and lock on a rate limit.
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    key = os.environ["MEMORYOS_JUDGE_API_KEY"]
    questions = [
        json.loads(line)
        for path in args.drafts
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    # A result is reused only for the same draft content; an edited or new draft is validated again,
    # and a draft that no longer exists drops out.
    previous = {}
    if args.out.exists():
        previous = {
            r["id"]: r for r in map(json.loads, args.out.read_text(encoding="utf-8").splitlines())
        }
    current = {
        q["id"]: previous[q["id"]]
        for q in questions
        if q["id"] in previous and previous[q["id"]].get("digest") == digest(q)
    }
    todo = [q for q in questions if q["id"] not in current]

    def safely(question: dict) -> dict:
        try:
            return validate(question, key)
        except Exception as error:  # noqa: BLE001 - one failed question must not lose the others
            # No digest: the next run validates it again.
            return {**question, "kept": False, "error": f"{type(error).__name__}: {error}"[:300]}

    def save() -> None:
        ordered = [current[q["id"]] for q in questions if q["id"] in current]
        args.out.write_text(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in ordered), encoding="utf-8"
        )

    with ThreadPoolExecutor(args.workers) as pool:
        for result in pool.map(safely, todo):
            current[result["id"]] = result
            save()
            state = "ERROR" if "error" in result else "kept" if result["kept"] else "REJECTED"
            print(f"{result['id']}: {state}", flush=True)
    save()
    errors = sum("error" in r for r in current.values())
    print(f"{sum(r['kept'] for r in current.values())} kept of {len(current)}, {errors} errors")


if __name__ == "__main__":
    main()
