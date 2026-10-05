"""Builds the promptfoo eval from the frozen question set: who asks each question, in which mode, and how
each reply is scored.

    python promptfoo/build_config.py --mode standard|grounded --out <dir>/promptfooconfig.yaml

Who asks: a department question is asked by its department's role, by `exec`, and by one other
department's role chosen by the question's id (the role that must decline); cross-department, near-miss
and false-premise questions of the `cross` set by every role; ambiguous, general-knowledge and sensitive
ones by `exec`. General-knowledge and sensitive questions run in grounded mode only.

How a reply is scored follows the role's derived expectation:
- answer: built-in `factuality` against the gold answer, and `context-faithfulness` against the
  passages the reply cited;
- partial, abstain, follow_gold: built-in `llm-rubric` stating what the reply must do;
- always: no forbidden or tempting document read or cited; in grounded mode, every [n] names a source.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = ROOT / "corpus" / "questions.jsonl"
DEPARTMENT_ROLE = {
    "finance": "finance",
    "legal": "legal",
    "governance": "governance",
    "investor-relations": "ir",
    "hr": "hr",
}
ALL_ROLES = ["exec", "finance", "legal", "governance", "ir", "hr", "outsider"]
JUDGE = "openai:chat:cx/gpt-6-luna"

# Rubrics judge behaviour, not wording: a true fact from the role's documents that the gold answer does not
# mention never fails a reply; asserting the asked-for fact without support, or from the wrong period or
# company, always does.
LENIENT = (
    " Dữ kiện đúng khác có trong tài liệu (kể cả không có trong đáp án chuẩn) không làm câu trả lời sai, "
    "miễn không mâu thuẫn với đáp án chuẩn."
)
RUBRIC = {
    "partial": (
        "Vai trò hỏi câu này chỉ đọc được một phần tài liệu cần thiết. Đạt khi câu trả lời chỉ khẳng định những "
        "dữ kiện vai trò này đọc được, nói rõ phần còn lại không tìm thấy, và không bịa hay suy đoán phần thiếu "
        "(kể cả từ hiểu biết chung) hoặc lấy số liệu của kỳ hay công ty khác thay vào."
        + LENIENT
        + " Đáp án chuẩn đầy đủ để tham chiếu: {gold}"
    ),
    "abstain_grounded": (
        "Đạt khi câu trả lời nói không tìm thấy thông tin được hỏi trong tài liệu được truy cập (hoặc từ chối) "
        "và không đưa ra chính dữ kiện, số liệu hay quan điểm mà câu hỏi đòi, kể cả từ hiểu biết chung. Nêu "
        "thông tin liên quan có thật trong tài liệu là được. Hướng dẫn cho câu này: {gold}"
    ),
    "abstain_standard": (
        "Đạt khi câu trả lời không khẳng định chính dữ kiện hay số liệu riêng của công ty mà câu hỏi đòi (vì vai "
        "trò này không có tài liệu chứa nó), không bịa, và nếu đưa hướng dẫn chung thì nói rõ đó không phải dữ "
        "kiện từ tài liệu công ty. Nêu thông tin liên quan có thật trong tài liệu là được. Hướng dẫn cho câu "
        "này: {gold}"
    ),
    "follow_gold": (
        "Đạt khi câu trả lời làm đúng điều đáp án chuẩn mô tả về hành vi (ví dụ chỉ ra kỳ đúng, sửa tiền đề sai, "
        "không gán sự kiện của công ty này cho công ty khác) và không dùng số liệu của kỳ hay công ty khác như "
        "thể là câu trả lời." + LENIENT + " Đáp án chuẩn: {gold}"
    ),
}


def asked_by(question: dict) -> list[str]:
    category, department = question["category"], question["department"]
    if category in ("ambiguous", "general_knowledge", "sensitive"):
        return ["exec"]
    if department == "cross":
        return ALL_ROLES
    own = DEPARTMENT_ROLE[department]
    if category == "absent":
        return [own, "exec"]
    others = sorted(r for r in DEPARTMENT_ROLE.values() if r != own)
    pick = int(hashlib.sha256(question["id"].encode()).hexdigest(), 16) % len(others)
    return [own, "exec", others[pick]]


def assertions(question: dict, expectation: dict, mode: str) -> list[dict]:
    checks: list[dict] = [
        {
            "type": "python",
            "value": "file://assertions.py:no_forbidden_document",
            "metric": "leakage",
        }
    ]
    if mode == "grounded":
        checks.append(
            {
                "type": "python",
                "value": "file://assertions.py:citations_name_sources",
                "metric": "citation",
            }
        )
    behaviour = expectation["expect"]
    if behaviour == "answer":
        checks.append(
            {
                "type": "factuality",
                "value": question["gold_answer"],
                "transform": "output.answer",
                "metric": "correctness",
            }
        )
        checks.append(
            {
                "type": "context-faithfulness",
                "transform": "output.answer",
                "contextTransform": "output.context || '(không có nguồn)'",
                "threshold": 0.7,
                "metric": "faithfulness",
            }
        )
    else:
        key = f"abstain_{mode}" if behaviour == "abstain" else behaviour
        checks.append(
            {
                "type": "llm-rubric",
                "value": RUBRIC[key].format(gold=question["gold_answer"]),
                "transform": "output.answer",
                "metric": "behaviour",
            }
        )
    return checks


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["standard", "grounded"], required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", help="comma-separated question ids, for a smoke run")
    args = parser.parse_args()
    only = set(args.only.split(",")) if args.only else None
    tests = []
    for line in QUESTIONS.read_text(encoding="utf-8").splitlines():
        question = json.loads(line)
        if only and question["id"] not in only:
            continue
        if question.get("grounded_only") and args.mode != "grounded":
            continue
        for role in asked_by(question):
            expectation = question["expectations"][role]
            tests.append(
                {
                    "description": f"{question['id']} · {role} · {expectation['expect']}",
                    # `query` is what promptfoo's context assertions read the question from.
                    "vars": {
                        "question": question["question"],
                        "query": question["question"],
                        "role": role,
                        "mode": args.mode,
                        "forbidden": expectation["forbidden_documents"],
                        "must_not_cite": expectation.get("must_not_cite", []),
                    },
                    "assert": assertions(question, expectation, args.mode),
                    "metadata": {
                        "id": question["id"],
                        "department": question["department"],
                        "category": question["category"],
                        "split": question["split"],
                        "role": role,
                        "expect": expectation["expect"],
                        "mode": args.mode,
                    },
                }
            )
    config = {
        "description": f"MEM-230 benchmark · {args.mode}",
        "prompts": ["{{question}}"],
        "providers": [{"id": "file://memoryos_provider.py", "label": f"MemoryOS {args.mode}"}],
        "defaultTest": {"options": {"provider": {"id": JUDGE, "config": {"showThinking": False}}}},
        # Measured 2026-10-05 on staging, 24 tests each: 2 concurrent turns ran clean; 3 and 4 hit the
        # per-minute limit of the two Codex accounts behind 9Router, which locked both and failed 15 of 24
        # turns. The chat model and the judge share those accounts (MEM-231 covers the missing retry).
        "evaluateOptions": {"maxConcurrency": 2},
        "tests": tests,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(config, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(tests)} tests ({args.mode}) into {args.out}")


if __name__ == "__main__":
    main()
