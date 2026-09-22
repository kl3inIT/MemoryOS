"""Deterministic text fitting for generated slides (MEM-122).

A model cannot see the deck it just wrote, so the renderer decides the type size instead of guessing one.
Widths come from the deck font's own advance widths — Calibri at 2048 units per em, which the
metric-compatible Carlito installed in the executor image renders identically — so a size chosen here holds
in PowerPoint and in the LibreOffice preview, and text that cannot fit at the smallest size in a ladder is
refused rather than rendered off the slide.
"""

from __future__ import annotations

#: Calibri advance widths in em, grouped by width; kerning and hinting are covered by _SAFETY below.
_ADVANCES: tuple[tuple[float, str], ...] = (
    (0.221, "'"), (0.226, " "), (0.229, "il"), (0.239, "j"), (0.250, ","), (0.252, ".I"), (0.268, ":;"),
    (0.291, "`"), (0.303, "()"), (0.305, "f"), (0.306, "-"), (0.307, "[]"), (0.314, "{}"), (0.319, "J"),
    (0.326, "!"), (0.335, "t"), (0.349, "r"), (0.386, "/\\"), (0.391, "s"), (0.395, "z"), (0.401, '"'),
    (0.420, "L"), (0.423, "c"), (0.433, "x"), (0.452, "v"), (0.453, "y"), (0.455, "k"), (0.459, "FS"),
    (0.460, "|"), (0.463, "?"), (0.468, "Z"), (0.471, "g"), (0.479, "a"), (0.487, "TY"), (0.488, "E"),
    (0.498, "#*+<=>^_e~"), (0.507, "$0123456789"), (0.517, "P"), (0.519, "X"), (0.520, "K"),
    (0.525, "bdhnpqu"), (0.527, "o"), (0.533, "C"), (0.543, "R"), (0.544, "B"), (0.567, "V"), (0.579, "A"),
    (0.615, "D"), (0.623, "H"), (0.631, "G"), (0.642, "U"), (0.646, "N"), (0.662, "O"), (0.673, "Q"),
    (0.682, "&"), (0.715, "%w"), (0.799, "m"), (0.855, "M"), (0.890, "W"), (0.894, "@"),
)
_EM: dict[str, float] = {character: width for width, characters in _ADVANCES for character in characters}
#: Vietnamese precomposed letters average 0.494 em and reach 0.603; the wider value keeps the estimate safe.
_NON_ASCII_EM = 0.55
#: Kerning, hinting and the wrap points a real layout engine picks are absorbed here.
_SAFETY = 1.03
LINE_SPACING = 1.22


def em(character: str) -> float:
    return _EM.get(character, _NON_ASCII_EM)


def width_inches(text: str, size_pt: float) -> float:
    """Rendered width of one line, in inches."""
    return sum(em(character) for character in text) * size_pt / 72.0 * _SAFETY


def wrap(text: str, width_in: float, size_pt: float) -> list[str]:
    """Greedy word wrap at the measured width; a word longer than the line gets its own line."""
    lines: list[str] = []
    for paragraph in text.split("\n"):
        current = ""
        for word in paragraph.split():
            candidate = word if not current else f"{current} {word}"
            if width_inches(candidate, size_pt) <= width_in or not current:
                current = candidate
            else:
                lines.append(current)
                current = word
        lines.append(current)
    return lines


def line_height(size_pt: float) -> float:
    return size_pt * LINE_SPACING / 72.0


def fits(text: str, width_in: float, height_in: float, size_pt: float) -> bool:
    return len(wrap(text, width_in, size_pt)) * line_height(size_pt) <= height_in


def fit(text: str, width_in: float, height_in: float, ladder: tuple[int, ...]) -> int | None:
    """The first size in the ladder at which the text fits the box, or None when none does."""
    for size in ladder:
        if fits(text, width_in, height_in, size):
            return size
    return None


def fit_block(texts: list[str], width_in: float, height_in: float, ladder: tuple[int, ...],
              gap_em: float = 0.45, indents: list[float] | None = None) -> int | None:
    """The first size at which every text, stacked with a gap between them, fits the box together."""
    offsets = indents or [0.0] * len(texts)
    for size in ladder:
        total = 0.0
        for text, indent in zip(texts, offsets):
            total += len(wrap(text, width_in - indent, size)) * line_height(size) + gap_em * size / 72.0
        if total <= height_in:
            return size
    return None


#: The average advance of Vietnamese and English body text, for translating a box into a character budget.
_BODY_EM = 0.48


def budget(width_in: float, height_in: float, size_pt: float) -> int:
    """Roughly how many characters of body text a box holds at a size, for error messages."""
    per_line = max(1, int(width_in / (_BODY_EM * size_pt / 72.0 * _SAFETY)))
    return per_line * max(1, int(height_in / line_height(size_pt)))
