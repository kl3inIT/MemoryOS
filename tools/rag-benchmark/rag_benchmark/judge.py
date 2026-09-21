"""
The answer judge: a few model calls per question at temperature 0, the majority verdict plus a
reason, so a score can be reread and a borderline answer does not swing on one call.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

import httpx

from .config import Config

SYSTEM = (
    "Bạn chấm câu trả lời của một trợ lý nội bộ. So sánh câu trả lời với đáp án chuẩn. "
    'Trả về JSON {"correct": true|false, "reason": "..."}. '
    "Đúng nghĩa là nêu đúng thông tin của đáp án chuẩn; khác cách diễn đạt vẫn tính đúng. "
    "Thiếu thông tin chính, sai số liệu, hoặc thêm thông tin không có trong đáp án chuẩn "
    "thì tính sai."
)
# The actor may read only part of the evidence; the gold answer names what it may answer. Requiring
# the reply to announce the absence of the rest marked correct answers wrong, so it is not
# required; what is required is that the reply does not supply the part it could not read.
PARTIAL = (
    " Người hỏi chỉ được đọc một phần tài liệu. Câu trả lời đúng khi nêu đúng phần đáp án "
    "chuẩn cho phép và không khẳng định phần còn lại. Không bắt buộc phải nói ra rằng phần "
    "còn lại thiếu. Tự điền phần còn lại bằng suy đoán hoặc kiến thức chung, kể cả khi điều "
    "đó có thể đúng, thì tính sai."
)


@dataclass(frozen=True)
class Verdict:
    correct: bool
    reason: str
    # How many trials returned the recorded verdict, out of how many ran. A verdict that was
    # not unanimous is a weaker measurement; hiding that presents one call as the truth.
    agreed: int = 1
    trials: int = 1


class Judge:
    """
    A judge with no key answers `unavailable`, so a run still reports retrieval without silently
    scoring zero.
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._available = bool(config.judge_api_key)

    @property
    def available(self) -> bool:
        return self._available

    def score(self, question: str, gold_answer: str, reply: str, partial: bool = False) -> Verdict:
        if not self._available:
            return Verdict(correct=False, reason="unavailable: no judge key configured")
        verdicts: list[Verdict] = []
        for _ in range(self._config.judge_trials):
            verdict = self._once(question, gold_answer, reply, partial)
            if verdict.reason.startswith(("judge call failed", "unparsed verdict")):
                # A broken call is not a vote: report it rather than let two trials outvote it.
                return verdict
            verdicts.append(verdict)
        agreed = sum(verdict.correct for verdict in verdicts)
        correct = agreed * 2 > len(verdicts)
        winner = next(verdict for verdict in verdicts if verdict.correct == correct)
        majority = agreed if correct else len(verdicts) - agreed
        if agreed not in (0, len(verdicts)):
            return Verdict(
                correct=correct,
                reason=f"{agreed}/{len(verdicts)} chấm đúng · {winner.reason}"[:500],
                agreed=majority,
                trials=len(verdicts),
            )
        return Verdict(
            correct=winner.correct,
            reason=winner.reason,
            agreed=majority,
            trials=len(verdicts),
        )

    def _once(self, question: str, gold_answer: str, reply: str, partial: bool) -> Verdict:
        body = {
            "model": self._config.judge_model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": SYSTEM + (PARTIAL if partial else "")},
                {
                    "role": "user",
                    "content": (
                        f"Câu hỏi:\n{question}\n\n"
                        f"Đáp án chuẩn:\n{gold_answer}\n\n"
                        f"Câu trả lời của trợ lý:\n{reply}"
                    ),
                },
            ],
        }
        response = httpx.post(
            f"{self._config.judge_base_url}/chat/completions",
            headers={"Authorization": f"Bearer {self._config.judge_api_key}"},
            json=body,
            timeout=self._config.timeout_seconds,
        )
        if response.status_code != 200:
            return Verdict(correct=False, reason=f"judge call failed ({response.status_code})")
        content = response.json()["choices"][0]["message"].get("content") or ""
        return self._parse(content)

    @staticmethod
    def _parse(content: str) -> Verdict:
        text = content.strip()
        # Models wrap JSON in a fenced block often enough that stripping it is cheaper than a retry.
        if text.startswith("```"):
            text = text.strip("`")
            text = text.split("\n", 1)[1] if "\n" in text else text
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            return Verdict(correct=False, reason=f"unparsed verdict: {content[:200]}")
        try:
            parsed = json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            return Verdict(correct=False, reason=f"unparsed verdict: {content[:200]}")
        return Verdict(
            correct=bool(parsed.get("correct")),
            reason=str(parsed.get("reason", ""))[:500],
        )
