"""
The answer judge: one model call per question, temperature 0, verdict plus reason so a score can be
reread.
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


@dataclass(frozen=True)
class Verdict:
    correct: bool
    reason: str


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

    def score(self, question: str, gold_answer: str, reply: str) -> Verdict:
        if not self._available:
            return Verdict(correct=False, reason="unavailable: no judge key configured")
        body = {
            "model": self._config.judge_model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": SYSTEM},
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
