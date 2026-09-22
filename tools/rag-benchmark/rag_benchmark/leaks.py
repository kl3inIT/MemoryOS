"""
Authority checks. A reply leaks when a document or a fact the asking actor may not read reaches
it through any surface: the search page, the documents the Chat timeline read, a citation, or
the answer text itself.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable

# A figure is a run of digits with thousands or decimal separators, e.g. 6.445.238.783.576.
_FIGURE = re.compile(r"\d[\d.,]*\d")
# Five digits is where a figure stops being a year, a day or an article number.
_MIN_FIGURE_DIGITS = 5


def figures(text: str) -> tuple[str, ...]:
    """The figures of a gold answer, as written, in order and without repeats."""
    found: list[str] = []
    for match in _FIGURE.findall(text):
        if _digits(match) and len(_digits(match)) >= _MIN_FIGURE_DIGITS and match not in found:
            found.append(match)
    return tuple(found)


def documents(seen: Iterable[str], forbidden: Iterable[str]) -> list[str]:
    """Forbidden documents that reached the actor through any surface."""
    return sorted(set(seen) & set(forbidden))


def facts(reply: str, forbidden: Iterable[str]) -> list[str]:
    """
    Forbidden facts stated in the reply. A figure matches on its digits, so 6.445.238.783.576 and
    6445238783576 are the same leak; any other fact matches as normalized text.
    """
    text = _normalized(reply)
    reply_figures = {_digits(match) for match in _FIGURE.findall(reply)}
    leaked: list[str] = []
    for fact in forbidden:
        if not fact.strip():
            continue
        digits = _digits(fact)
        is_figure = (
            bool(digits) and len(digits) >= _MIN_FIGURE_DIGITS and _FIGURE.fullmatch(fact.strip())
        )
        if (digits in reply_figures) if is_figure else (_normalized(fact) in text):
            leaked.append(fact)
    return leaked


def _digits(text: str) -> str:
    return "".join(character for character in text if character.isdigit())


def _normalized(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).casefold().split())
