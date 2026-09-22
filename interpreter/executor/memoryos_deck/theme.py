"""The corporate deck template: one geometry, one type ramp, three palettes (MEM-122).

The model never chooses a coordinate, a colour or a type size; it chooses a theme name. Everything here is
fixed so two decks generated a week apart, by different models, are the same document format.
"""

from __future__ import annotations

from dataclasses import dataclass

# PowerPoint's 16:9 page, in the exact EMU it stores (12 192 000 x 6 858 000), so a full-bleed shape lands
# on the page edge instead of a rounded fraction of an inch short of it.
SLIDE_W = 12_192_000 / 914_400
SLIDE_H = 6_858_000 / 914_400
MARGIN = 0.85
CONTENT_W = SLIDE_W - 2 * MARGIN

TITLE_Y = 0.62
TITLE_H = 0.82
RULE_Y = 1.50
RULE_W = 1.45
RULE_H = 0.05
BODY_Y = 1.82
BODY_BOTTOM = 6.36
BODY_H = BODY_BOTTOM - BODY_Y
FOOTNOTE_H = 0.26
FOOTER_Y = 6.86
FOOTER_H = 0.32

# Type ramps, largest first: the renderer takes the first size whose text fits its box.
DECK_TITLE = (40, 36, 32, 28)
SLIDE_TITLE = (28, 26, 24, 22)
SECTION_TITLE = (40, 36, 32, 28)
LEAD = (17, 16, 15, 14)
BULLET = (19, 18, 17, 16, 15, 14)
COLUMN_BULLET = (17, 16, 15, 14, 13)
COLUMN_HEADING = (17, 16, 15)
METRIC_VALUE = (34, 30, 26, 22)
METRIC_LABEL = (13, 12, 11)
METRIC_DELTA = (12, 11, 10)
TABLE_TEXT = (13, 12, 11, 10)
QUOTE = (26, 24, 22, 20, 18)
CAPTION = (13, 12, 11)
SUBTITLE = (18, 17, 16, 15)
META = (13, 12)
FOOTNOTE = (11, 10)
FOOTER = (10,)
SOURCE_ITEM = (14, 13, 12, 11)
# The smallest size any body text is allowed to reach; below this a slide is too full, not too small.
MIN_READABLE_PT = 10


@dataclass(frozen=True)
class Palette:
    accent: str
    accent_dark: str
    accent_soft: str
    ink: str
    muted: str
    line: str
    surface: str
    on_accent: str


PALETTES: dict[str, Palette] = {
    "corporate": Palette(accent="1D4ED8", accent_dark="1E3A8A", accent_soft="DBEAFE", ink="0F172A",
                         muted="475569", line="E2E8F0", surface="F8FAFC", on_accent="FFFFFF"),
    "slate": Palette(accent="334155", accent_dark="0F172A", accent_soft="E2E8F0", ink="0F172A",
                     muted="52606D", line="DFE3E8", surface="F7F8FA", on_accent="FFFFFF"),
    "emerald": Palette(accent="047857", accent_dark="064E3B", accent_soft="D1FAE5", ink="0F241C",
                       muted="42675A", line="DCE7E2", surface="F6FAF8", on_accent="FFFFFF"),
}
DEFAULT_THEME = "corporate"

# Calibri is rendered by the metric-compatible Carlito installed in the executor image, so the fitted sizes
# hold in the LibreOffice preview as well as in PowerPoint, and it covers Vietnamese.
FONT = "Calibri"

LABELS: dict[str, dict[str, str]] = {
    "vi": {"sources": "Nguồn tham khảo", "agenda": "Nội dung", "cited": "Nguồn"},
    "en": {"sources": "Sources", "agenda": "Agenda", "cited": "Sources"},
}
DEFAULT_LANGUAGE = "vi"


def palette(name: str) -> Palette:
    return PALETTES[name]


def labels(language: str) -> dict[str, str]:
    return LABELS[language]
