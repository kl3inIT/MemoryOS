"""Scores written minutes against the answer key.

One judge call per meeting only lines the written items up with the key's: which key item each written item
records, if any. Every metric is then counted, so the judge decides sameness, never quality. The test's vars name
the key file (`key`); the provider's output carries the written items, the speakers and the numbered lines.
"""

from __future__ import annotations

import json
import os
import re
import time
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

JUDGE_MODEL = os.environ.get("MINUTES_JUDGE_MODEL", "cx/gpt-6-luna")
# The same waits as the RAG provider: 35 s outlasts a 9Router model lock, the longer ones a Codex usage limit.
RETRY_WAITS = (35, 70, 300)

ALIGN = """You compare the minutes of a Vietnamese meeting with an answer key a person wrote for it.

For every WRITTEN item, say which KEY item records the same thing: the same decision, or the same piece of work
(the same task on the same object). Wording, detail, owner and deadline may differ; ignore them. The transcript
comes from speech recognition, so the same name or term may be spelled differently in the two lists ("path spec"
and "pubspec", "Luxy" and "Lark"); the quoted lines and their numbers help to tell. A written item that records
something the key does not have matches nothing. Several written items may match one key item when they repeat
it. A decision only matches a decision and a piece of work only matches a piece of work.

Look at each written item in turn: give the key item it records, or null, with a reason of a few words.

Answer with JSON only:
{"matches": {"W1": {"key": "K3", "why": "..."}, "W2": {"key": null, "why": "..."}, ...}} naming every written item once.

KEY
%s

WRITTEN
%s
"""


def fold(text: str | None) -> str:
    """Compared without case, accents' encoding or spacing, as the product compares names."""
    return " ".join(unicodedata.normalize("NFC", text or "").lower().split())


def _item_line(prefix: str, number: int, kind: str, item: dict, lines: dict[int, str]) -> str:
    said = lines.get(item.get("line") or 0, "")
    return f'{prefix}{number} [{kind}] {item["text"]} (line {item.get("line")}: "{said[:200]}")'


def _judge(prompt: str) -> dict:
    url = os.environ.get("OPENAI_BASE_URL", "").rstrip("/") + "/chat/completions"
    body = json.dumps({
        "model": JUDGE_MODEL,
        "stream": False,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + os.environ.get("OPENAI_API_KEY", "")}
    for wait in (*RETRY_WAITS, None):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, body, headers), timeout=600) as response:
                answer = json.load(response)["choices"][0]["message"]["content"]
            found = re.search(r"\{.*\}", answer, re.S)
            if not found:
                # No alignment is not an alignment of nothing: retried, then surfaced as a failed grading.
                raise json.JSONDecodeError("the judge answered without JSON", answer, 0)
            return json.loads(found.group(0))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError):
            if wait is None:
                raise
            time.sleep(wait)
    return {}


def _voice_name(owner: dict | None, speakers: dict[tuple[str, str], str | None]) -> str | None:
    if not owner:
        return None
    if "name" in owner:
        return fold(owner["name"])
    return fold(speakers.get((owner["track"], owner["label"]))) or None


def same_owner(key: dict | None, written: dict | None, speakers: dict[tuple[str, str], str | None]) -> bool:
    """The same voice, the same name, a name that is the voice's name, or both none."""
    if not key or not written:
        return not key and not written
    if "track" in key and "track" in written:
        return (key["track"], key["label"]) == (written["track"], written["label"])
    named_key, named_written = _voice_name(key, speakers), _voice_name(written, speakers)
    return named_key is not None and named_key == named_written


def unverifiable(key: dict | None, written: dict | None, speakers: dict[tuple[str, str], str | None]) -> bool:
    """A name in the key against a voice that has no name: whether that voice is the person cannot be told."""
    return bool(key and "name" in key and written and "track" in written
                and not speakers.get((written["track"], written["label"])))


def _ratio(part: int, whole: int) -> float | None:
    return part / whole if whole else None


def score(output: dict, context: dict) -> dict:
    key = json.loads(Path(context["vars"]["key"]).read_text(encoding="utf-8"))
    lines = {line["n"]: line["text"] for line in output.get("lines") or []}
    speakers = {(s["track"], s["label"]): s.get("name") for s in output.get("speakers") or []}

    keyed = [("decision", item) for item in key.get("decisions") or []] + \
            [("action", item) for item in key.get("actions") or []]
    written = [("decision", item) for item in output.get("decisions") or []] + \
              [("action", item) for item in output.get("actions") or []]
    prompt = ALIGN % (
        "\n".join(_item_line("K", n, kind, item, lines) for n, (kind, item) in enumerate(keyed, 1)) or "(none)",
        "\n".join(_item_line("W", n, kind, item, lines) for n, (kind, item) in enumerate(written, 1)) or "(none)",
    )
    answered = (_judge(prompt).get("matches") or {}) if written and keyed else {}
    matches = {w: (m.get("key") if isinstance(m, dict) else m) for w, m in answered.items()}

    matched_to: dict[int, list[int]] = {}
    for n, (kind, _) in enumerate(written, 1):
        target = matches.get(f"W{n}")
        number = int(target[1:]) if isinstance(target, str) and re.fullmatch(r"K\d+", target) else 0
        if 1 <= number <= len(keyed) and keyed[number - 1][0] == kind:
            matched_to.setdefault(number, []).append(n)

    counts: dict[str, int] = {}

    def add(name: str, value: int = 1) -> None:
        counts[name] = counts.get(name, 0) + value

    for kind in ("decision", "action"):
        add(f"{kind}_key", sum(1 for k, _ in keyed if k == kind))
        add(f"{kind}_written", sum(1 for k, _ in written if k == kind))
    for number, writers in matched_to.items():
        kind, item = keyed[number - 1]
        add(f"{kind}_found")
        add(f"{kind}_matching", len(writers))
        add("duplicates", len(writers) - 1)
        if kind == "action":
            # The written item nearest the key's line stands for the others.
            best = min(writers, key=lambda w: abs((written[w - 1][1].get("line") or 0) - (item.get("line") or 0)))
            given = written[best - 1][1].get("owner")
            wanted = item.get("owner")
            if unverifiable(wanted, given, speakers):
                # The key names a person and the minutes give a voice nobody named: it may be that person.
                add("owner_unverifiable")
            else:
                add("owner_checked")
                if same_owner(wanted, given, speakers):
                    add("owner_right")
                elif given:
                    add("owner_wrong")
                else:
                    add("owner_missing")
    quoted = [item for _, item in written if item.get("quote")]
    add("quotes", len(quoted))
    add("quotes_in_line", sum(1 for item in quoted if fold(item["quote"]) in fold(lines.get(item.get("line") or 0))))

    named = {
        "action_recall": _ratio(counts.get("action_found", 0), counts["action_key"]),
        "action_precision": _ratio(counts.get("action_matching", 0), counts["action_written"]),
        "decision_recall": _ratio(counts.get("decision_found", 0), counts["decision_key"]),
        "decision_precision": _ratio(counts.get("decision_matching", 0), counts["decision_written"]),
        "owner_accuracy": _ratio(counts.get("owner_right", 0), counts.get("owner_checked", 0)),
        "owner_wrong": _ratio(counts.get("owner_wrong", 0), counts.get("owner_checked", 0)),
        "owner_unverifiable": _ratio(counts.get("owner_unverifiable", 0),
                                     counts.get("owner_checked", 0) + counts.get("owner_unverifiable", 0)),
        "quote_in_line": _ratio(counts.get("quotes_in_line", 0), counts.get("quotes", 0)),
        "duplicates": _ratio(counts.get("duplicates", 0), counts["decision_written"] + counts["action_written"]),
    }
    named = {name: round(value, 4) for name, value in named.items() if value is not None}
    recall = named.get("action_recall", 1.0)
    return {
        "pass": True,
        "score": recall,
        "namedScores": named,
        "reason": json.dumps({"counts": counts, "matches": matches}, ensure_ascii=False),
    }
