"""Render a validated deck plan onto the corporate template (MEM-122).

Every coordinate, colour and type size comes from :mod:`memoryos_deck.theme`; the plan only carries content.
Text is fitted by :mod:`memoryos_deck.measure`: a field that still does not fit at the smallest size in its
ladder fails the render, naming the slide and what to shorten, instead of producing a slide whose text runs
off the edge — the fault a model cannot see in the file it just wrote.
"""

from __future__ import annotations

from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml import parse_xml
from pptx.oxml.ns import nsdecls, qn
from pptx.util import Emu, Inches, Pt

from . import measure, theme
from .plan import PlanError

#: Written to the deck's core properties so `check-pptx` can tell a rendered deck from a hand-built one.
MARKER = "memoryos-deck"
BULLET_CHARS = ("•", "–")
BULLET_INDENT = (0.0, 0.42)
SOURCES_PER_SLIDE = 10
LONG_BULLET_CHARS = 200
MONOTONY_RUN = 5


class _Deck:
    def __init__(self, plan: dict[str, object]) -> None:
        self.plan = plan
        self.palette = theme.palette(str(plan["theme"]))
        self.labels = theme.labels(str(plan["language"]))
        self.sources: list[dict[str, str]] = list(plan["sources"])  # type: ignore[arg-type]
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.presentation = Presentation()
        self.presentation.slide_width = Inches(theme.SLIDE_W)
        self.presentation.slide_height = Inches(theme.SLIDE_H)
        self.blank = self.presentation.slide_layouts[6]

    # --- primitives -------------------------------------------------------------------------------------

    def _slide(self, background: str | None = None):
        slide = self.presentation.slides.add_slide(self.blank)
        fill = slide.background.fill
        fill.solid()
        fill.fore_color.rgb = RGBColor.from_string(background or "FFFFFF")
        return slide

    def _box(self, slide, left: float, top: float, width: float, height: float, anchor=MSO_ANCHOR.TOP):
        box = slide.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))
        frame = box.text_frame
        frame.word_wrap = True
        frame.vertical_anchor = anchor
        frame.margin_left = frame.margin_right = frame.margin_top = frame.margin_bottom = 0
        return frame

    def _write(self, paragraph, text: str, size: int, color: str, *, bold: bool = False,
               italic: bool = False, spacing: float = measure.LINE_SPACING) -> None:
        paragraph.line_spacing = spacing
        run = paragraph.add_run()
        run.text = text
        run.font.size = Pt(size)
        run.font.bold = bold
        run.font.italic = italic
        run.font.name = theme.FONT
        run.font.color.rgb = RGBColor.from_string(color)

    def _text(self, slide, left: float, top: float, width: float, height: float, text: str, size: int,
              color: str, *, bold: bool = False, italic: bool = False, align=PP_ALIGN.LEFT,
              anchor=MSO_ANCHOR.TOP) -> None:
        frame = self._box(slide, left, top, width, height, anchor)
        paragraph = frame.paragraphs[0]
        paragraph.alignment = align
        self._write(paragraph, text, size, color, bold=bold, italic=italic)

    def _rect(self, slide, left: float, top: float, width: float, height: float, color: str,
              shape=MSO_SHAPE.RECTANGLE, radius: float | None = None):
        box = slide.shapes.add_shape(shape, Inches(left), Inches(top), Inches(width), Inches(height))
        # The theme style reference carries a drop shadow and an outline that LibreOffice and PowerPoint
        # resolve differently; dropping it leaves only the fill and line set here, so both render the same.
        style = box._element.find(qn("p:style"))
        if style is not None:
            box._element.remove(style)
        box.fill.solid()
        box.fill.fore_color.rgb = RGBColor.from_string(color)
        box.line.fill.background()
        box.shadow.inherit = False
        if radius is not None:
            box.adjustments[0] = radius
        if box.has_text_frame:
            box.text_frame.text = ""
        return box

    def _bullet(self, paragraph, level: int, size: int) -> None:
        """A real PowerPoint bullet: typed glyphs stop being a list the moment the user edits the deck."""
        indent = Inches(0.3)
        properties = paragraph._p.get_or_add_pPr()
        properties.set("marL", str(int(Inches(BULLET_INDENT[level]) + indent)))
        properties.set("indent", str(int(-indent)))
        properties.append(parse_xml(f'<a:buClr {nsdecls("a")}><a:srgbClr val="{self.palette.accent}"/></a:buClr>'))
        properties.append(parse_xml(f'<a:buSzPct {nsdecls("a")} val="85000"/>'))
        properties.append(parse_xml(f'<a:buFont {nsdecls("a")} typeface="Arial"/>'))
        properties.append(parse_xml(f'<a:buChar {nsdecls("a")} char="{BULLET_CHARS[level]}"/>'))

    def _fail(self, where: str, what: str, width: float, height: float, ladder: tuple[int, ...]) -> None:
        self.errors.append(
            f"{where}: {what} does not fit at {ladder[-1]} pt; keep it under about "
            f"{measure.budget(width, height, ladder[-1])} characters or move content to another slide")

    # --- chrome -----------------------------------------------------------------------------------------

    def _title(self, slide, where: str, text: str) -> None:
        size = measure.fit(text, theme.CONTENT_W, theme.TITLE_H, theme.SLIDE_TITLE)
        if size is None:
            self._fail(where, "the slide title", theme.CONTENT_W, theme.TITLE_H, theme.SLIDE_TITLE)
            size = theme.SLIDE_TITLE[-1]
        self._text(slide, theme.MARGIN, theme.TITLE_Y, theme.CONTENT_W, theme.TITLE_H, text, size,
                   self.palette.ink, bold=True)
        self._rect(slide, theme.MARGIN, theme.RULE_Y, theme.RULE_W, theme.RULE_H, self.palette.accent)

    def _footer(self, slide, number: int) -> None:
        deck = str(self.plan["footer"] or self.plan["title"])
        confidentiality = str(self.plan["confidentiality"])
        left = deck if not confidentiality else f"{deck}  ·  {confidentiality}"
        size = theme.FOOTER[0]
        self._rect(slide, theme.MARGIN, theme.FOOTER_Y - 0.14, theme.CONTENT_W, 0.012, self.palette.line)
        self._text(slide, theme.MARGIN, theme.FOOTER_Y, theme.CONTENT_W - 1.0, theme.FOOTER_H,
                   left[:120], size, self.palette.muted)
        self._text(slide, theme.SLIDE_W - theme.MARGIN - 0.9, theme.FOOTER_Y, 0.9, theme.FOOTER_H,
                   str(number), size, self.palette.muted, align=PP_ALIGN.RIGHT)

    def _cites(self, slide, cites: list[int]) -> float:
        """The footnote under the body; returns the body height it consumed."""
        if not cites:
            return 0.0
        parts = []
        for cite in cites:
            source = self.sources[cite - 1]
            parts.append(f"[{cite}] {source['label']}" + (f", {source['detail']}" if source["detail"] else ""))
        text = f"{self.labels['cited']}: " + "  ".join(parts)
        size = theme.FOOTNOTE[0]
        while len(measure.wrap(text, theme.CONTENT_W, size)) > 1 and size > theme.FOOTNOTE[-1]:
            size -= 1
        lines = measure.wrap(text, theme.CONTENT_W, size)
        if len(lines) > 1:
            text = lines[0].rstrip() + "…"
        self._text(slide, theme.MARGIN, theme.BODY_BOTTOM + 0.05, theme.CONTENT_W, theme.FOOTNOTE_H, text,
                   size, self.palette.muted)
        return theme.FOOTNOTE_H + 0.05

    # --- slide kinds ------------------------------------------------------------------------------------

    def _title_slide(self, slide, entry: dict[str, object]) -> None:
        self._rect(slide, 0, theme.SLIDE_H - 1.15, theme.SLIDE_W, 1.15, self.palette.accent)
        self._rect(slide, theme.MARGIN, 1.55, 1.9, 0.07, self.palette.accent)
        title = str(entry.get("title") or self.plan["title"])
        size = measure.fit(title, theme.CONTENT_W, 2.0, theme.DECK_TITLE)
        if size is None:
            self._fail("slide 1 (title)", "the deck title", theme.CONTENT_W, 2.0, theme.DECK_TITLE)
            size = theme.DECK_TITLE[-1]
        self._text(slide, theme.MARGIN, 1.95, theme.CONTENT_W, 2.0, title, size, self.palette.ink, bold=True)
        subtitle = str(entry.get("subtitle") or self.plan["subtitle"])
        if subtitle:
            self._text(slide, theme.MARGIN, 4.05, theme.CONTENT_W, 0.9, subtitle,
                       measure.fit(subtitle, theme.CONTENT_W, 0.9, theme.SUBTITLE) or theme.SUBTITLE[-1],
                       self.palette.muted)
        meta = "  ·  ".join(part for part in (str(self.plan["author"]), str(self.plan["date"]),
                                              str(self.plan["confidentiality"])) if part)
        if meta:
            self._text(slide, theme.MARGIN, 5.15, theme.CONTENT_W, 0.5, meta, theme.META[0],
                       self.palette.muted)

    def _agenda_slide(self, slide, entry: dict[str, object], where: str) -> None:
        items: list[str] = list(entry["items"])  # type: ignore[arg-type]
        self._title(slide, where, str(entry.get("title") or self.labels["agenda"]))
        height = theme.BODY_H
        numbered = [f"{index + 1}.  {item}" for index, item in enumerate(items)]
        size = measure.fit_block(numbered, theme.CONTENT_W - 0.5, height, theme.BULLET, gap_em=0.85)
        if size is None:
            self._fail(where, "the agenda", theme.CONTENT_W - 0.5, height, theme.BULLET)
            return
        frame = self._box(slide, theme.MARGIN, theme.BODY_Y, theme.CONTENT_W, height)
        for index, item in enumerate(items):
            paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
            paragraph.space_after = Pt(size * 0.85)
            self._write(paragraph, f"{index + 1}.", size, self.palette.accent, bold=True)
            self._write(paragraph, f"   {item}", size, self.palette.ink)

    def _section_slide(self, slide, entry: dict[str, object], where: str, number: int) -> None:
        self._rect(slide, 0, 0, theme.SLIDE_W, theme.SLIDE_H, self.palette.accent)
        self._rect(slide, theme.MARGIN, 2.55, 1.9, 0.07, self.palette.on_accent)
        title = str(entry["title"])
        size = measure.fit(title, theme.CONTENT_W, 2.1, theme.SECTION_TITLE)
        if size is None:
            self._fail(where, "the section title", theme.CONTENT_W, 2.1, theme.SECTION_TITLE)
            size = theme.SECTION_TITLE[-1]
        self._text(slide, theme.MARGIN, 2.95, theme.CONTENT_W, 2.1, title, size, self.palette.on_accent,
                   bold=True)
        subtitle = str(entry.get("subtitle", ""))
        if subtitle:
            self._text(slide, theme.MARGIN, 2.0, theme.CONTENT_W, 0.5, subtitle, theme.META[0],
                       self.palette.accent_soft)
        self._text(slide, theme.SLIDE_W - theme.MARGIN - 0.9, theme.FOOTER_Y, 0.9, theme.FOOTER_H,
                   str(number), theme.FOOTER[0], self.palette.accent_soft, align=PP_ALIGN.RIGHT)

    def _bullets_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._title(slide, where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.85, theme.LEAD)
            if size is None:
                self._fail(where, "the lead sentence", theme.CONTENT_W, 0.85, theme.LEAD)
                return
            used = len(measure.wrap(lead, theme.CONTENT_W, size)) * measure.line_height(size) + 0.22
            self._text(slide, theme.MARGIN, top, theme.CONTENT_W, used, lead, size, self.palette.muted)
            top += used
            height -= used
        bullets: list[dict[str, object]] = list(entry["bullets"])  # type: ignore[arg-type]
        texts = [str(bullet["text"]) for bullet in bullets]
        indents = [BULLET_INDENT[int(bullet["level"])] + 0.3 for bullet in bullets]
        size = measure.fit_block(texts, theme.CONTENT_W, height, theme.BULLET, gap_em=0.7, indents=indents)
        if size is None:
            self._fail(where, "the bullet list", theme.CONTENT_W, height, theme.BULLET)
            return
        frame = self._box(slide, theme.MARGIN, top, theme.CONTENT_W, height)
        for index, bullet in enumerate(bullets):
            level = int(bullet["level"])
            paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
            paragraph.space_after = Pt(size * 0.7)
            self._write(paragraph, str(bullet["text"]), size if level == 0 else size - 2,
                        self.palette.ink if level == 0 else self.palette.muted)
            self._bullet(paragraph, level, size)

    def _columns_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._title(slide, where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.6, theme.LEAD) or theme.LEAD[-1]
            self._text(slide, theme.MARGIN, top, theme.CONTENT_W, 0.6, lead, size, self.palette.muted)
            top += 0.75
            height -= 0.75
        gap = 0.55
        width = (theme.CONTENT_W - gap) / 2
        columns: list[dict[str, object]] = list(entry["columns"])  # type: ignore[arg-type]
        for position, column in enumerate(columns):
            left = theme.MARGIN + position * (width + gap)
            self._rect(slide, left, top, width, 0.44, self.palette.accent_soft)
            heading = str(column["heading"])
            size = measure.fit(heading, width - 0.4, 0.44, theme.COLUMN_HEADING)
            if size is None:
                self._fail(f"{where} column {position + 1}", "the heading", width - 0.4, 0.44,
                           theme.COLUMN_HEADING)
                return
            self._text(slide, left + 0.2, top + 0.06, width - 0.4, 0.44, heading, size,
                       self.palette.accent_dark, bold=True)
            bullets: list[dict[str, object]] = list(column["bullets"])  # type: ignore[arg-type]
            texts = [str(bullet["text"]) for bullet in bullets]
            body_top = top + 0.62
            body_height = height - 0.62
            fitted = measure.fit_block(texts, width, body_height, theme.COLUMN_BULLET, gap_em=0.6,
                                       indents=[0.3] * len(texts))
            if fitted is None:
                self._fail(f"{where} column {position + 1}", "the bullet list", width, body_height,
                           theme.COLUMN_BULLET)
                return
            frame = self._box(slide, left, body_top, width, body_height)
            for index, bullet in enumerate(bullets):
                paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
                paragraph.space_after = Pt(fitted * 0.6)
                self._write(paragraph, str(bullet["text"]), fitted, self.palette.ink)
                self._bullet(paragraph, 0, fitted)

    def _metrics_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._title(slide, where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        lead = str(entry.get("lead", ""))
        if lead:
            size = measure.fit(lead, theme.CONTENT_W, 0.6, theme.LEAD) or theme.LEAD[-1]
            self._text(slide, theme.MARGIN, top, theme.CONTENT_W, 0.6, lead, size, self.palette.muted)
            top += 0.8
            height -= 0.8
        metrics: list[dict[str, str]] = list(entry["metrics"])  # type: ignore[arg-type]
        gap = 0.4
        width = (theme.CONTENT_W - gap * (len(metrics) - 1)) / len(metrics)
        card = min(2.4, height)
        top += max(0.0, (height - card) / 2)
        for position, metric in enumerate(metrics):
            left = theme.MARGIN + position * (width + gap)
            self._rect(slide, left, top, width, card, self.palette.surface, MSO_SHAPE.ROUNDED_RECTANGLE, 0.06)
            self._rect(slide, left, top, 0.075, card, self.palette.accent)
            inner = width - 0.7
            value = metric["value"]
            size = measure.fit(value, inner, 1.0, theme.METRIC_VALUE)
            if size is None:
                self._fail(f"{where} metric {position + 1}", "the value", inner, 1.0, theme.METRIC_VALUE)
                return
            self._text(slide, left + 0.45, top + 0.42, inner, 1.0, value, size, self.palette.accent_dark,
                       bold=True)
            label = metric["label"]
            label_size = measure.fit(label, inner, 0.7, theme.METRIC_LABEL)
            if label_size is None:
                self._fail(f"{where} metric {position + 1}", "the label", inner, 0.7, theme.METRIC_LABEL)
                return
            self._text(slide, left + 0.45, top + 1.5, inner, 0.7, label, label_size, self.palette.muted)
            if metric["delta"]:
                self._text(slide, left + 0.45, top + card - 0.55, inner, 0.4, metric["delta"],
                           theme.METRIC_DELTA[0], self.palette.ink, bold=True)

    def _table_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._title(slide, where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        header: list[str] = list(entry["header"])  # type: ignore[arg-type]
        rows: list[list[str]] = list(entry["rows"])  # type: ignore[arg-type]
        caption = str(entry.get("caption", ""))
        if caption:
            height -= 0.45
        column_width = theme.CONTENT_W / len(header)
        cells = [cell for row in rows for cell in row] + header
        size = None
        for candidate in theme.TABLE_TEXT:
            row_height = measure.line_height(candidate) + 0.22
            if row_height * (len(rows) + 1) > height:
                continue
            if all(len(measure.wrap(cell, column_width - 0.3, candidate)) <= 1 for cell in cells):
                size = candidate
                break
        if size is None:
            self._fail(where, "the table", column_width - 0.3, height, theme.TABLE_TEXT)
            return
        row_height = measure.line_height(size) + 0.22
        table = slide.shapes.add_table(len(rows) + 1, len(header), Inches(theme.MARGIN), Inches(top),
                                       Inches(theme.CONTENT_W), Inches(row_height * (len(rows) + 1))).table
        table.horz_banding = False
        for index in range(len(header)):
            table.columns[index].width = Emu(int(Inches(column_width)))
        for index in range(len(rows) + 1):
            table.rows[index].height = Emu(int(Inches(row_height)))
        for position, text in enumerate(header):
            self._cell(table.cell(0, position), text, size, self.palette.on_accent, self.palette.accent,
                       bold=True)
        for row_index, row in enumerate(rows, start=1):
            background = "FFFFFF" if row_index % 2 else self.palette.surface
            for position, text in enumerate(row):
                self._cell(table.cell(row_index, position), text, size, self.palette.ink, background)
        if caption:
            self._text(slide, theme.MARGIN, top + row_height * (len(rows) + 1) + 0.12, theme.CONTENT_W, 0.4,
                       caption, theme.CAPTION[0], self.palette.muted, italic=True)

    def _cell(self, cell, text: str, size: int, color: str, background: str, *, bold: bool = False) -> None:
        cell.fill.solid()
        cell.fill.fore_color.rgb = RGBColor.from_string(background)
        cell.vertical_anchor = MSO_ANCHOR.MIDDLE
        cell.margin_left = cell.margin_right = Inches(0.14)
        cell.margin_top = cell.margin_bottom = Inches(0.04)
        frame = cell.text_frame
        frame.word_wrap = False
        paragraph = frame.paragraphs[0]
        self._write(paragraph, text, size, color, bold=bold, spacing=1.0)

    def _image_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._title(slide, where, str(entry["title"]))
        top = theme.BODY_Y
        height = theme.BODY_H - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        caption = str(entry.get("caption", ""))
        if caption:
            height -= 0.5
        picture = slide.shapes.add_picture(str(entry["path"]), Inches(0), Inches(0))
        scale = min(Inches(theme.CONTENT_W) / picture.width, Inches(height) / picture.height)
        picture.width = Emu(int(picture.width * scale))
        picture.height = Emu(int(picture.height * scale))
        picture.left = Emu(int(Inches(theme.SLIDE_W) / 2 - picture.width / 2))
        picture.top = Emu(int(Inches(top) + (Inches(height) - picture.height) / 2))
        if caption:
            self._text(slide, theme.MARGIN, top + height + 0.1, theme.CONTENT_W, 0.45, caption,
                       theme.CAPTION[0], self.palette.muted, align=PP_ALIGN.CENTER, italic=True)

    def _quote_slide(self, slide, entry: dict[str, object], where: str) -> None:
        title = str(entry.get("title", ""))
        if title:
            self._title(slide, where, title)
        top = theme.BODY_Y if title else 2.2
        height = (theme.BODY_BOTTOM - top) - self._cites(slide, list(entry.get("cites", [])))  # type: ignore[arg-type]
        text = str(entry["text"])
        size = measure.fit(f"“{text}”", theme.CONTENT_W - 1.0, height - 0.7, theme.QUOTE)
        if size is None:
            self._fail(where, "the quote", theme.CONTENT_W - 1.0, height - 0.7, theme.QUOTE)
            return
        self._rect(slide, theme.MARGIN, top, 0.075, height, self.palette.accent)
        self._text(slide, theme.MARGIN + 0.55, top, theme.CONTENT_W - 1.0, height - 0.7, f"“{text}”", size,
                   self.palette.ink, italic=True, anchor=MSO_ANCHOR.MIDDLE)
        attribution = str(entry.get("attribution", ""))
        if attribution:
            self._text(slide, theme.MARGIN + 0.55, top + height - 0.55, theme.CONTENT_W - 1.0, 0.45,
                       f"— {attribution}", theme.META[0], self.palette.muted)

    def _closing_slide(self, slide, entry: dict[str, object], where: str) -> None:
        self._rect(slide, 0, 0, theme.SLIDE_W, theme.SLIDE_H, self.palette.accent_dark)
        title = str(entry["title"])
        size = measure.fit(title, theme.CONTENT_W, 1.6, theme.SECTION_TITLE)
        if size is None:
            self._fail(where, "the closing title", theme.CONTENT_W, 1.6, theme.SECTION_TITLE)
            size = theme.SECTION_TITLE[-1]
        self._text(slide, theme.MARGIN, 2.85, theme.CONTENT_W, 1.6, title, size, self.palette.on_accent,
                   bold=True, align=PP_ALIGN.CENTER)
        subtitle = str(entry.get("subtitle", ""))
        if subtitle:
            self._text(slide, theme.MARGIN, 4.35, theme.CONTENT_W, 0.7, subtitle,
                       measure.fit(subtitle, theme.CONTENT_W, 0.7, theme.SUBTITLE) or theme.SUBTITLE[-1],
                       self.palette.accent_soft, align=PP_ALIGN.CENTER)

    def _sources_slide(self, entries: list[tuple[int, dict[str, str]]], number: int, part: int,
                       parts: int) -> None:
        slide = self._slide()
        title = self.labels["sources"] + (f" ({part}/{parts})" if parts > 1 else "")
        self._title(slide, "sources", title)
        texts = [f"[{index}]  {source['label']}" + (f" — {source['detail']}" if source["detail"] else "")
                 for index, source in entries]
        size = measure.fit_block(texts, theme.CONTENT_W, theme.BODY_H, theme.SOURCE_ITEM, gap_em=0.7)
        if size is None:
            self.errors.append("the sources list does not fit even when split across slides; shorten the "
                               "source labels")
            return
        frame = self._box(slide, theme.MARGIN, theme.BODY_Y, theme.CONTENT_W, theme.BODY_H)
        for position, text in enumerate(texts):
            paragraph = frame.paragraphs[0] if position == 0 else frame.add_paragraph()
            paragraph.space_after = Pt(size * 0.7)
            self._write(paragraph, text, size, self.palette.muted)
        self._footer(slide, number)

    # --- deck -------------------------------------------------------------------------------------------

    def build(self):
        slides: list[dict[str, object]] = list(self.plan["slides"])  # type: ignore[arg-type]
        if slides[0]["type"] != "title":
            self.warnings.append("the deck does not open with a 'title' slide")
        if not self.sources:
            self.warnings.append("the deck declares no sources; a deck built from indexed documents should "
                                 "cite them with 'sources' and 'cites'")
        run = 0
        for position, entry in enumerate(slides):
            kind = str(entry["type"])
            run = run + 1 if kind == "bullets" else 0
            if run == MONOTONY_RUN:
                self.warnings.append(f"slides[{position}]: {MONOTONY_RUN} bullet slides in a row; a "
                                     "'metrics', 'table' or 'image' slide carries this better")
            number = position + 1
            where = f"slides[{position}] ({kind})"
            plain = kind not in ("title", "section", "closing")
            slide = self._slide()
            if kind == "title":
                self._title_slide(slide, entry)
            elif kind == "agenda":
                self._agenda_slide(slide, entry, where)
            elif kind == "section":
                self._section_slide(slide, entry, where, number)
            elif kind == "bullets":
                self._bullets_slide(slide, entry, where)
            elif kind == "columns":
                self._columns_slide(slide, entry, where)
            elif kind == "metrics":
                self._metrics_slide(slide, entry, where)
            elif kind == "table":
                self._table_slide(slide, entry, where)
            elif kind == "image":
                self._image_slide(slide, entry, where)
            elif kind == "quote":
                self._quote_slide(slide, entry, where)
            elif kind == "closing":
                self._closing_slide(slide, entry, where)
            if plain:
                self._footer(slide, number)
            note = str(entry.get("note", ""))
            if note:
                slide.notes_slide.notes_text_frame.text = note
            for bullet in list(entry.get("bullets", [])):  # type: ignore[arg-type]
                if len(str(bullet["text"])) > LONG_BULLET_CHARS:
                    self.warnings.append(f"{where}: a bullet is {len(str(bullet['text']))} characters; "
                                         "a slide bullet is a claim, not a paragraph")
        number = len(slides)
        for part, start in enumerate(range(0, len(self.sources), SOURCES_PER_SLIDE), start=1):
            chunk = [(index + 1, source) for index, source
                     in enumerate(self.sources[start:start + SOURCES_PER_SLIDE], start=start)]
            number += 1
            parts = (len(self.sources) + SOURCES_PER_SLIDE - 1) // SOURCES_PER_SLIDE
            self._sources_slide(chunk, number, part, parts)
        properties = self.presentation.core_properties
        properties.title = str(self.plan["title"])
        properties.author = str(self.plan["author"]) or MARKER
        properties.category = MARKER
        properties.comments = f"Rendered by {MARKER} from a deck plan"
        if self.errors:
            raise PlanError(self.errors)
        return self.presentation


def render(plan: dict[str, object], path: str) -> dict[str, object]:
    """Render the validated plan to `path`; PlanError names every slide that does not fit."""
    deck = _Deck(plan)
    presentation = deck.build()
    presentation.save(path)
    return {"file": Path(path).name, "rendered": True, "slides": len(presentation.slides),
            "warning_count": len(deck.warnings), "warnings": deck.warnings}
