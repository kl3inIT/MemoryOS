"""Scoring that needs nothing beyond the standard library, so its tests run anywhere."""

from __future__ import annotations

import json
import re

# Patterns of idioms that changed under pandas 3, numpy 2 or the executor's guards. Counting them shows what the
# model writes by habit; a pattern is a hint, not a verdict, and does not affect correctness.
OLD_IDIOMS = {
    "inplace_on_column": r"\]\s*\.\s*\w+\([^)]*inplace\s*=\s*True",
    "chained_setitem": r"\[[^\]]+\]\s*\[[^\]]+\]\s*=[^=]",
    "freq_M_Q_H": r"""(resample|freq\s*=)\s*\(?\s*['"](M|Q|H|A|Y|T|S)['"]""",
    "fillna_method": r"fillna\([^)]*method\s*=",
    "np_removed_alias": r"np\.(NaN|NAN|float_|trapz|in1d|product|row_stack)\b",
    "object_dtype_check": r"""(==\s*['"]?object['"]?|==\s*['"]O['"]|include\s*=\s*\[?\s*['"]object['"])""",
    "dayfirst": r"dayfirst\s*=\s*True",
}


def matches(expected: object, actual: object) -> bool:
    """Structural equality; numbers compare with a small tolerance, dictionary keys as strings."""
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            return False
        keyed = {str(key): value for key, value in actual.items()}
        return set(keyed) == set(expected) and all(matches(value, keyed[key]) for key, value in expected.items())
    if isinstance(expected, list):
        return (
            isinstance(actual, list)
            and len(actual) == len(expected)
            and all(matches(e, a) for e, a in zip(expected, actual, strict=True))
        )
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        if isinstance(actual, bool):
            return False
        try:
            return abs(float(actual) - float(expected)) <= 1e-6 * max(1.0, abs(float(expected))) + 0.006
        except (TypeError, ValueError):
            return False
    return expected == actual


def answer_of(stdout: str) -> object:
    """The `answer` of the last stdout line that is a JSON object holding one, else None."""
    for line in reversed(stdout.strip().splitlines()):
        try:
            payload = json.loads(line)
        except ValueError:
            continue
        if isinstance(payload, dict) and "answer" in payload:
            return payload["answer"]
    return None


def code_of(reply: str) -> str:
    """The first fenced code block of a reply, or the whole reply when there is none."""
    found = re.findall(r"```(?:python)?\s*\n(.*?)```", reply, re.S)
    return found[0] if found else reply


def old_idioms(code: str) -> list[str]:
    return sorted(name for name, pattern in OLD_IDIOMS.items() if re.search(pattern, code))
