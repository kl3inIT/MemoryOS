"""Render a validated deck plan as one self-contained HTML deck (MEM-122).

The same plan, the same template, the same refusals as :mod:`memoryos_deck.render` — a second output format,
not a second design. Geometry is shared literally: a slide is a 13.333 x 7.5 in box and every element sits
at the inch coordinates the PowerPoint renderer uses, so the HTML deck and the `.pptx` are the same document.

HTML is the fast path. It needs no LibreOffice, opens in any browser, prints to a pixel-identical PDF
through ``@page``, and is a single file: the CSS is inline and pictures are embedded as data URIs, because
the executor has no network and the file has to keep working after the user downloads it.
"""

from __future__ import annotations

import base64
import html
import mimetypes
from pathlib import Path

from . import measure, theme
from .plan import PlanError

BULLET_CHARS = ("•", "–")
BULLET_INDENT = (0.0, 0.42)
SOURCES_PER_SLIDE = 10
LONG_BULLET_CHARS = 200
MONOTONY_RUN = 5
#: Read by a browser as "this deck came from render-deck", the HTML counterpart of the pptx core property.
MARKER = "memoryos-deck"

_DOCUMENT = """<!DOCTYPE html>
<html lang="{language}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="generator" content="{marker}">
<title>{title}</title>
<style>
:root {{
  --accent: #{accent}; --accent-dark: #{accent_dark}; --accent-soft: #{accent_soft};
  --ink: #{ink}; --muted: #{muted}; --line: #{line}; --surface: #{surface}; --on-accent: #{on_accent};
  --scale: 1;
}}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
body {{
  background: #0b1220; padding: 28px 0 56px;
  font-family: Calibri, Carlito, "Segoe UI", system-ui, sans-serif;
  -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility;
}}
.deck {{ display: flex; flex-direction: column; align-items: center; scroll-snap-type: y mandatory; }}
.slide {{
  position: relative; width: {slide_w}in; height: {slide_h}in; flex: none; overflow: hidden;
  background: #fff; color: var(--ink); scroll-snap-align: center;
  box-shadow: 0 18px 44px rgba(0, 0, 0, .45); border-radius: 3px;
  transform: scale(var(--scale)); transform-origin: top center;
  margin-bottom: calc(24px + (var(--scale) - 1) * {slide_h}in);
}}
.e {{ position: absolute; white-space: pre-wrap; }}
.fill {{ position: absolute; }}
.right {{ text-align: right; }}
.center {{ text-align: center; }}
.bold {{ font-weight: 700; }}
.italic {{ font-style: italic; }}
ul.bullets {{ list-style: none; }}
ul.bullets li {{ position: relative; }}
ul.bullets .mk {{ position: absolute; left: 0; color: var(--accent); }}
table.grid {{ position: absolute; border-collapse: collapse; table-layout: fixed; }}
table.grid th {{ background: var(--accent); color: var(--on-accent); font-weight: 700; text-align: left; }}
table.grid th, table.grid td {{ padding: 0 .14in; overflow: hidden; white-space: nowrap; }}
table.grid tr.alt td {{ background: var(--surface); }}
img.figure {{ position: absolute; object-fit: contain; }}
.hint {{
  position: fixed; right: 16px; bottom: 14px; z-index: 9; color: #94a3b8; font-size: 12px;
  background: rgba(15, 23, 42, .78); padding: 6px 10px; border-radius: 999px;
}}
@page {{ size: {slide_w}in {slide_h}in; margin: 0; }}
@media print {{
  body {{ background: #fff; padding: 0; }}
  .hint {{ display: none; }}
  .slide {{ transform: none; margin: 0; box-shadow: none; border-radius: 0; break-after: page; }}
}}
</style>
</head>
<body>
<div class="deck">
{slides}
</div>
<div class="hint">↑ ↓ / PgUp PgDn · Ctrl+P → PDF</div>
<script>
const slides = [...document.querySelectorAll('.slide')];
function scale() {{
  const fit = Math.min((window.innerWidth - 48) / ({slide_w} * 96), 1);
  document.documentElement.style.setProperty('--scale', fit.toFixed(4));
}}
function go(step) {{
  const middle = window.innerHeight / 2;
  const current = slides.findIndex(slide => slide.getBoundingClientRect().bottom > middle);
  const next = slides[Math.min(slides.length - 1, Math.max(0, (current < 0 ? 0 : current) + step))];
  if (next) next.scrollIntoView({{ behavior: 'smooth', block: 'center' }});
}}
addEventListener('resize', scale);
addEventListener('keydown', event => {{
  if (['ArrowDown', 'ArrowRight', 'PageDown', ' '].includes(event.key)) {{ event.preventDefault(); go(1); }}
  if (['ArrowUp', 'ArrowLeft', 'PageUp'].includes(event.key)) {{ event.preventDefault(); go(-1); }}
  if (event.key === 'f') document.documentElement.requestFullscreen?.();
}});
scale();
</script>
</body>
</html>
"""


def _escape(text: str) -> str:
    return html.escape(str(text), quote=True)


def _data_uri(path: str) -> str:
    media = mimetypes.guess_type(path)[0] or "image/png"
    return f"data:{media};base64," + base64.b64encode(Path(path).read_bytes()).decode("ascii")


class _Deck:
    def __init__(self, plan: dict[str, object]) -> None:
        self.plan = plan
        self.palette = theme.palette(str(plan["theme"]))
        self.labels = theme.labels(str(plan["language"]))
        self.sources: list[dict[str, str]] = list(plan["sources"])  # type: ignore[arg-type]
        self.errors: list[str] = []
        self.warnings: list[str] = []

    # --- primitives -------------------------------------------------------------------------------------

    @staticmethod
    def _at(left: float, top: float, width: float, height: float) -> str:
        return f"left:{left:.4f}in;top:{top:.4f}in;width:{width:.4f}in;height:{height:.4f}in"

    def _text(self, left: float, top: float, width: float, height: float, text: str, size: float,
              color: str, *, bold: bool = False, italic: bool = False, align: str = "",
              middle: bool = False) -> str:
        classes = " ".join(part for part in ("e", align, "bold" if bold else "", "italic" if italic else "")
                           if part)
        style = (f"{self._at(left, top, width, height)};font-size:{size}pt;color:var(--{color});"
                 f"line-height:{measure.LINE_SPACING}")
        if middle:
            style += ";display:flex;flex-direction:column;justify-content:center"
        return f'<div class="{classes}" style="{style}">{_escape(text)}</div>'

    def _rect(self, left: float, top: float, width: float, height: float, color: str,
              radius: float = 0.0) -> str:
        style = f"{self._at(left, top, width, height)};background:var(--{color})"
        if radius:
            style += f";border-radius:{radius}in"
        return f'<div class="fill" style="{style}"></div>'

    def _fail(self, where: str, what: str, width: float, height: float, ladder: tuple[int, ...]) -> None:
        self.errors.append(
            f"{where}: {what} does not fit at {ladder[-1]} pt; keep it under about "
            f"{measure.budget(width, height, ladder[-1])} characters or move content to another slide")

    # --- chrome -----------------------------------------------------------------------------------------

    def _title(self, where: str, text: str) -> list[str]:
        size = measure.fit(text, theme.CONTENT_W, theme.TITLE_H, theme.SLIDE_TITLE)
        if size is None:
            self._fail(where, "the slide title", theme.CONTENT_W, theme.TITLE_H, theme.SLIDE_TITLE)
            size = theme.SLIDE_TITLE[-1]
        return [self._text(theme.MARGIN, theme.TITLE_Y, theme.CONTENT_W, theme.TITLE_H, text, size, "ink",
                           bold=True),
                self._rect(theme.MARGIN, theme.RULE_Y, theme.RULE_W, theme.RULE_H, "accent")]

    def _footer(self, number: int) -> list[str]:
        deck = str(self.plan["footer"] or self.plan["title"])
        confidentiality = str(self.plan["confidentiality"])
        left = deck if not confidentiality else f"{deck}  ·  {confidentiality}"
        size = theme.FOOTER[0]
        return [self._rect(theme.MARGIN, theme.FOOTER_Y - 0.14, theme.CONTENT_W, 0.012, "line"),
                self._text(theme.MARGIN, theme.FOOTER_Y, theme.CONTENT_W - 1.0, theme.FOOTER_H, left[:120],
                           size, "muted"),
                self._text(theme.SLIDE_W - theme.MARGIN - 0.9, theme.FOOTER_Y, 0.9, theme.FOOTER_H,
                           str(number), size, "muted", align="right")]

    def _cites(self, parts: list[str], cites: list[int]) -> float:
        if not cites:
            return 0.0
        listed = []
        for cite in cites:
            source = self.sources[cite - 1]
            listed.append(f"[{cite}] {source['label']}" + (f", {source['detail']}" if source["detail"] else ""))
        text = f"{self.labels['cited']}: " + "  ".join(listed)
        size = theme.FOOTNOTE[0]
        while len(measure.wrap(text, theme.CONTENT_W, size)) > 1 and size > theme.FOOTNOTE[-1]:
            size -= 1
        lines = measure.wrap(text, theme.CONTENT_W, size)
        if len(lines) > 1:
            text = lines[0].rstrip() + "…"
        parts.append(self._text(theme.MARGIN, theme.BODY_BOTTOM + 0.05, theme.CONTENT_W, theme.FOOTNOTE_H,
                                text, size, "muted"))
        return theme.FOOTNOTE_H + 0.05

    def _list(self, left: float, top: float, width: float, height: float, items: list[tuple[str, int]],
              size: float, gap: float) -> str:
        rows = []
        for text, level in items:
            colour = "ink" if level == 0 else "muted"
            rows.append(f'<li style="margin:0 0 {gap * size:.1f}pt {BULLET_INDENT[level]}in;'
                        f"padding-left:0.3in;font-size:{size if level == 0 else size - 2}pt;"
                        f'color:var(--{colour})">'
                        f'<span class="mk">{BULLET_CHARS[level]}</span>{_escape(text)}</li>')
        style = f"{self._at(left, top, width, height)};line-height:{measure.LINE_SPACING}"
        return f'<ul class="bullets e" style="{style}">{"".join(rows)}</ul>'

    # --- slide kinds ------------------------------------------------------------------------------------

    def _title_slide(self, entry: dict[str, object]) -> list[str]:
        parts = [self._rect(0, theme.SLIDE_H - 1.15, theme.SLIDE_W, 1.15, "accent"),
                 self._rect(theme.MARGIN, 1.55, 1.9, 0.07, "accent")]
        title = str(entry.get("title") or self.plan["title"])
        size = measure.fit(title, theme.CONTENT_W, 2.0, theme.DECK_TITLE)
        if size is None:
            self._fail("slide 1 (title)", "the deck title", theme.CONTENT_W, 2.0, theme.DECK_TITLE)
            size = theme.DECK_TITLE[-1]
        parts.append(self._text(theme.MARGIN, 1.95, theme.CONTENT_W, 2.0, title, size, "ink", bold=True))
        subtitle = str(entry.get("subtitle") or self.plan["subtitle"])
        if subtitle:
            parts.append(self._text(theme.MARGIN, 4.05, theme.CONTENT_W, 0.9, subtitle,
                                    measure.fit(subtitle, theme.CONTENT_W, 0.9, theme.SUBTITLE)
                                    or theme.SUBTITLE[-1], "muted"))
        meta = "  ·  ".join(part for part in (str(self.plan["author"]), str(self.plan["date"]),
                                              str(self.plan["confidentiality"])) if part)
        if meta:
            parts.append(self._text(theme.MARGIN, 5.15, theme.CONTENT_W, 0.5, meta, theme.META[0], "muted"))
        return parts

    def _agenda_slide(self, entry: dict[str, object], where: str) -> list[str]:
        items: list[str] = list(entry["items"])  # type: ignore[arg-type]
        parts = self._title(where, str(entry.get("title") or self.labels["agenda"]))
        numbered = [f"{index + 1}.  {item}" for index, item in enumerate(items)]
        size = measure.fit_block(numbered, theme.CONTENT_W - 0.5, theme.BODY_H, theme.BULLET, gap_em=0.85)
        if size is None:
            self._fail(where, "the agenda", theme.CONTENT_W - 0.5, theme.BODY_H, theme.BULLET)
            return parts
        rows = "".join(
            f'<li style="margin:0 0 {0.85 * size}pt 0;padding-left:0.42in;font-size:{size}pt">'
            f'<span class="mk" style="font-weight:700">{index + 1}.</span>'
            f"{_escape(item)}</li>" for index, item in enumerate(items))
        parts.append(f'<ul class="bullets e" style="{self._at(theme.MARGIN, theme.BODY_Y, theme.CONTENT_W, theme.BODY_H)};'
                     f'line-height:{measure.LINE_SPACING}">{rows}</ul>')
        return parts

    def _section_slide(self, entry: dict[str, object], where: str, number: int) -> list[str]:
        parts = [self._rect(0, 0, theme.SLIDE_W, theme.SLIDE_H, "accent"),
                 self._rect(theme.MARGIN, 2.55, 1.9, 0.07, "on-accent")]
        title = str(entry["title"])
        size = measure.fit(title, theme.CONTENT_W, 2.1, theme.SECTION_TITLE)
        if size is None:
            self._fail(where, "the section title", theme.CONTENT_W, 2.1, theme.SECTION_TITLE)
            size = theme.SECTION_TITLE[-1]
        parts.append(self._text(theme.MARGIN, 2.95, theme.CONTENT_W, 2.1, title, size, "on-accent",
                                bold=True))
        subtitle = str(entry.get("subtitle", ""))
        if subtitle:
            parts.append(self._text(theme.MARGIN, 2.0, theme.CONTENT_W, 0.5, subtitle, theme.META[0],
                                    "accent-soft"))
        parts.append(self._text(theme.SLIDE_W - theme.MARGIN - 0.9, theme.FOOTER_Y, 0.9, theme.FOOTER_H,
                                str(number), theme.FOOTER[0], "accent-soft", align="right"))
        return parts

    def _bullets_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = self._title(where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.85, theme.LEAD)
            if size is None:
                self._fail(where, "the lead sentence", theme.CONTENT_W, 0.85, theme.LEAD)
                return parts
            used = len(measure.wrap(lead, theme.CONTENT_W, size)) * measure.line_height(size) + 0.22
            parts.append(self._text(theme.MARGIN, top, theme.CONTENT_W, used, lead, size, "muted"))
            top += used
            height -= used
        bullets: list[dict[str, object]] = list(entry["bullets"])  # type: ignore[arg-type]
        texts = [str(bullet["text"]) for bullet in bullets]
        levels = [int(bullet["level"]) for bullet in bullets]
        size = measure.fit_block(texts, theme.CONTENT_W, height, theme.BULLET, gap_em=0.7,
                                 indents=[BULLET_INDENT[level] + 0.3 for level in levels])
        if size is None:
            self._fail(where, "the bullet list", theme.CONTENT_W, height, theme.BULLET)
            return parts
        parts.append(self._list(theme.MARGIN, top, theme.CONTENT_W, height, list(zip(texts, levels)), size,
                                0.7))
        return parts

    def _columns_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = self._title(where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.6, theme.LEAD) or theme.LEAD[-1]
            parts.append(self._text(theme.MARGIN, top, theme.CONTENT_W, 0.6, lead, size, "muted"))
            top += 0.75
            height -= 0.75
        gap = 0.55
        width = (theme.CONTENT_W - gap) / 2
        for position, column in enumerate(list(entry["columns"])):  # type: ignore[arg-type]
            left = theme.MARGIN + position * (width + gap)
            heading = str(column["heading"])
            size = measure.fit(heading, width - 0.4, 0.44, theme.COLUMN_HEADING)
            if size is None:
                self._fail(f"{where} column {position + 1}", "the heading", width - 0.4, 0.44,
                           theme.COLUMN_HEADING)
                return parts
            parts.append(self._rect(left, top, width, 0.44, "accent-soft"))
            parts.append(self._text(left + 0.2, top + 0.06, width - 0.4, 0.44, heading, size, "accent-dark",
                                    bold=True))
            bullets: list[dict[str, object]] = list(column["bullets"])  # type: ignore[arg-type]
            texts = [str(bullet["text"]) for bullet in bullets]
            fitted = measure.fit_block(texts, width, height - 0.62, theme.COLUMN_BULLET, gap_em=0.6,
                                       indents=[0.3] * len(texts))
            if fitted is None:
                self._fail(f"{where} column {position + 1}", "the bullet list", width, height - 0.62,
                           theme.COLUMN_BULLET)
                return parts
            parts.append(self._list(left, top + 0.62, width, height - 0.62, [(text, 0) for text in texts],
                                    fitted, 0.6))
        return parts

    def _metrics_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = self._title(where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.6, theme.LEAD) or theme.LEAD[-1]
            parts.append(self._text(theme.MARGIN, top, theme.CONTENT_W, 0.6, lead, size, "muted"))
            top += 0.8
            height -= 0.8
        metrics: list[dict[str, str]] = list(entry["metrics"])  # type: ignore[arg-type]
        gap = 0.4
        width = (theme.CONTENT_W - gap * (len(metrics) - 1)) / len(metrics)
        card = min(2.4, height)
        top += max(0.0, (height - card) / 2)
        for position, metric in enumerate(metrics):
            left = theme.MARGIN + position * (width + gap)
            inner = width - 0.7
            size = measure.fit(metric["value"], inner, 1.0, theme.METRIC_VALUE)
            label_size = measure.fit(metric["label"], inner, 0.7, theme.METRIC_LABEL)
            if size is None or label_size is None:
                self._fail(f"{where} metric {position + 1}", "the value or label", inner, 1.0,
                           theme.METRIC_VALUE)
                return parts
            parts.append(self._rect(left, top, width, card, "surface", radius=0.08))
            parts.append(self._rect(left, top, 0.075, card, "accent"))
            parts.append(self._text(left + 0.45, top + 0.42, inner, 1.0, metric["value"], size,
                                    "accent-dark", bold=True))
            parts.append(self._text(left + 0.45, top + 1.5, inner, 0.7, metric["label"], label_size,
                                    "muted"))
            if metric["delta"]:
                parts.append(self._text(left + 0.45, top + card - 0.55, inner, 0.4, metric["delta"],
                                        theme.METRIC_DELTA[0], "ink", bold=True))
        return parts

    def _table_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = self._title(where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        header: list[str] = list(entry["header"])  # type: ignore[arg-type]
        rows: list[list[str]] = list(entry["rows"])  # type: ignore[arg-type]
        caption = str(entry.get("caption", ""))
        if caption:
            height -= 0.45
        column_width = theme.CONTENT_W / len(header)
        cells = [cell for row in rows for cell in row] + header
        size = None
        for candidate in theme.TABLE_TEXT:
            if (measure.line_height(candidate) + 0.22) * (len(rows) + 1) > height:
                continue
            if all(len(measure.wrap(cell, column_width - 0.3, candidate)) <= 1 for cell in cells):
                size = candidate
                break
        if size is None:
            self._fail(where, "the table", column_width - 0.3, height, theme.TABLE_TEXT)
            return parts
        row_height = measure.line_height(size) + 0.22
        head = "".join(f'<th style="width:{column_width:.4f}in">{_escape(cell)}</th>' for cell in header)
        body = "".join(
            f'<tr class="{"alt" if index % 2 == 0 else ""}" style="height:{row_height:.4f}in">'
            + "".join(f"<td>{_escape(cell)}</td>" for cell in row) + "</tr>"
            for index, row in enumerate(rows))
        style = (f"{self._at(theme.MARGIN, top, theme.CONTENT_W, row_height * (len(rows) + 1))};"
                 f"font-size:{size}pt")
        parts.append(f'<table class="grid" style="{style}">'
                     f'<tr style="height:{row_height:.4f}in">{head}</tr>{body}</table>')
        if caption:
            parts.append(self._text(theme.MARGIN, top + row_height * (len(rows) + 1) + 0.12,
                                    theme.CONTENT_W, 0.4, caption, theme.CAPTION[0], "muted", italic=True))
        return parts

    def _image_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = self._title(where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        caption = str(entry.get("caption", ""))
        if caption:
            height -= 0.5
        source = _data_uri(str(entry["path"]))
        parts.append(f'<img class="figure" alt="{_escape(entry["title"])}" '
                     f'style="{self._at(theme.MARGIN, top, theme.CONTENT_W, height)}" src="{source}">')
        if caption:
            parts.append(self._text(theme.MARGIN, top + height + 0.1, theme.CONTENT_W, 0.45, caption,
                                    theme.CAPTION[0], "muted", align="center", italic=True))
        return parts

    def _quote_slide(self, entry: dict[str, object], where: str) -> list[str]:
        title = str(entry.get("title", ""))
        parts = self._title(where, title) if title else []
        top = theme.BODY_Y if title else 2.2
        height = (theme.BODY_BOTTOM - top) - self._cites(parts, list(entry.get("cites", [])))  # type: ignore[arg-type]
        text = f"“{entry['text']}”"
        size = measure.fit(text, theme.CONTENT_W - 1.0, height - 0.7, theme.QUOTE)
        if size is None:
            self._fail(where, "the quote", theme.CONTENT_W - 1.0, height - 0.7, theme.QUOTE)
            return parts
        parts.append(self._rect(theme.MARGIN, top, 0.075, height, "accent"))
        parts.append(self._text(theme.MARGIN + 0.55, top, theme.CONTENT_W - 1.0, height - 0.7, text, size,
                                "ink", italic=True, middle=True))
        attribution = str(entry.get("attribution", ""))
        if attribution:
            parts.append(self._text(theme.MARGIN + 0.55, top + height - 0.55, theme.CONTENT_W - 1.0, 0.45,
                                    f"— {attribution}", theme.META[0], "muted"))
        return parts

    def _closing_slide(self, entry: dict[str, object], where: str) -> list[str]:
        parts = [self._rect(0, 0, theme.SLIDE_W, theme.SLIDE_H, "accent-dark")]
        title = str(entry["title"])
        size = measure.fit(title, theme.CONTENT_W, 1.6, theme.SECTION_TITLE)
        if size is None:
            self._fail(where, "the closing title", theme.CONTENT_W, 1.6, theme.SECTION_TITLE)
            size = theme.SECTION_TITLE[-1]
        parts.append(self._text(theme.MARGIN, 2.85, theme.CONTENT_W, 1.6, title, size, "on-accent",
                                bold=True, align="center"))
        subtitle = str(entry.get("subtitle", ""))
        if subtitle:
            parts.append(self._text(theme.MARGIN, 4.35, theme.CONTENT_W, 0.7, subtitle,
                                    measure.fit(subtitle, theme.CONTENT_W, 0.7, theme.SUBTITLE)
                                    or theme.SUBTITLE[-1], "accent-soft", align="center"))
        return parts

    def _sources_slide(self, entries: list[tuple[int, dict[str, str]]], number: int, part: int,
                       parts_total: int) -> list[str]:
        title = self.labels["sources"] + (f" ({part}/{parts_total})" if parts_total > 1 else "")
        parts = self._title("sources", title)
        texts = [f"[{index}]  {source['label']}" + (f" — {source['detail']}" if source["detail"] else "")
                 for index, source in entries]
        size = measure.fit_block(texts, theme.CONTENT_W, theme.BODY_H, theme.SOURCE_ITEM, gap_em=0.7)
        if size is None:
            self.errors.append("the sources list does not fit even when split across slides; shorten the "
                               "source labels")
            return parts
        rows = "".join(f'<li style="margin:0 0 {0.7 * size}pt 0;font-size:{size}pt;color:var(--muted)">'
                       f"{_escape(text)}</li>" for text in texts)
        parts.append(f'<ul class="bullets e" style="{self._at(theme.MARGIN, theme.BODY_Y, theme.CONTENT_W, theme.BODY_H)};'
                     f'line-height:{measure.LINE_SPACING}">{rows}</ul>')
        parts.extend(self._footer(number))
        return parts

    # --- deck -------------------------------------------------------------------------------------------

    def build(self) -> str:
        slides: list[dict[str, object]] = list(self.plan["slides"])  # type: ignore[arg-type]
        if slides[0]["type"] != "title":
            self.warnings.append("the deck does not open with a 'title' slide")
        if not self.sources:
            self.warnings.append("the deck declares no sources; a deck built from indexed documents should "
                                 "cite them with 'sources' and 'cites'")
        rendered: list[str] = []
        run = 0
        for position, entry in enumerate(slides):
            kind = str(entry["type"])
            run = run + 1 if kind == "bullets" else 0
            if run == MONOTONY_RUN:
                self.warnings.append(f"slides[{position}]: {MONOTONY_RUN} bullet slides in a row; a "
                                     "'metrics', 'table' or 'image' slide carries this better")
            number = position + 1
            where = f"slides[{position}] ({kind})"
            if kind == "title":
                parts = self._title_slide(entry)
            elif kind == "agenda":
                parts = self._agenda_slide(entry, where)
            elif kind == "section":
                parts = self._section_slide(entry, where, number)
            elif kind == "bullets":
                parts = self._bullets_slide(entry, where)
            elif kind == "columns":
                parts = self._columns_slide(entry, where)
            elif kind == "metrics":
                parts = self._metrics_slide(entry, where)
            elif kind == "table":
                parts = self._table_slide(entry, where)
            elif kind == "image":
                parts = self._image_slide(entry, where)
            elif kind == "quote":
                parts = self._quote_slide(entry, where)
            else:
                parts = self._closing_slide(entry, where)
            if kind not in ("title", "section", "closing"):
                parts.extend(self._footer(number))
            note = str(entry.get("note", ""))
            aria = f' aria-label="{_escape(note)}"' if note else ""
            rendered.append(f'<section class="slide" data-kind="{kind}"{aria}>' + "".join(parts) + "</section>")
            for bullet in list(entry.get("bullets", [])):  # type: ignore[arg-type]
                if len(str(bullet["text"])) > LONG_BULLET_CHARS:
                    self.warnings.append(f"{where}: a bullet is {len(str(bullet['text']))} characters; "
                                         "a slide bullet is a claim, not a paragraph")
        number = len(slides)
        total = (len(self.sources) + SOURCES_PER_SLIDE - 1) // SOURCES_PER_SLIDE
        for part, start in enumerate(range(0, len(self.sources), SOURCES_PER_SLIDE), start=1):
            chunk = [(index + 1, source) for index, source
                     in enumerate(self.sources[start:start + SOURCES_PER_SLIDE], start=start)]
            number += 1
            rendered.append('<section class="slide" data-kind="sources">'
                            + "".join(self._sources_slide(chunk, number, part, total)) + "</section>")
        if self.errors:
            raise PlanError(self.errors)
        return _DOCUMENT.format(language=str(self.plan["language"]), marker=MARKER,
                                title=_escape(self.plan["title"]), slide_w=f"{theme.SLIDE_W:.4f}",
                                slide_h=f"{theme.SLIDE_H:.4f}", slides="\n".join(rendered),
                                **{field: getattr(self.palette, field) for field in
                                   ("accent", "accent_dark", "accent_soft", "ink", "muted", "line",
                                    "surface", "on_accent")})


def render(plan: dict[str, object], path: str) -> dict[str, object]:
    """Write the validated plan as one self-contained HTML deck; PlanError names what does not fit."""
    deck = _Deck(plan)
    document = deck.build()
    Path(path).write_text(document, encoding="utf-8")
    slides = document.count('<section class="slide"')
    return {"file": Path(path).name, "rendered": True, "slides": slides,
            "warning_count": len(deck.warnings), "warnings": deck.warnings}
