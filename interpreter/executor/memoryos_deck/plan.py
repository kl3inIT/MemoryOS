"""The deck plan the model writes, and its refusal rules (MEM-122).

A plan is one JSON object::

    {
      "title": "Báo cáo kết quả kinh doanh 2024",
      "subtitle": "Tổng hợp từ báo cáo tài chính đã lập chỉ mục",
      "author": "Phòng Tài chính",
      "date": "2026-09-22",
      "confidentiality": "Nội bộ",
      "theme": "corporate",            # corporate | slate | emerald
      "language": "vi",                # vi | en
      "sources": [{"label": "Báo cáo tài chính 2024.pdf", "detail": "trang 12-14"}],
      "slides": [
        {"type": "title"},
        {"type": "agenda",  "items": ["Kết quả", "Rủi ro", "Kế hoạch"]},
        {"type": "section", "title": "Kết quả tài chính"},
        {"type": "bullets", "title": "Doanh thu", "lead": "…", "bullets": ["…", {"text": "…", "level": 1}],
         "cites": [1], "note": "ghi chú cho người trình bày"},
        {"type": "columns", "title": "…", "columns": [{"heading": "Thuận lợi", "bullets": ["…"]},
                                                       {"heading": "Rủi ro",   "bullets": ["…"]}]},
        {"type": "metrics", "title": "…", "metrics": [{"value": "1.234 tỷ", "label": "Doanh thu", "delta": "+12%"}]},
        {"type": "table",   "title": "…", "columns": ["Quý", "Doanh thu"], "rows": [["Q1", "310 tỷ"]]},
        {"type": "image",   "title": "…", "path": "doanh-thu.png", "caption": "…"},
        {"type": "quote",   "text": "…", "attribution": "…"},
        {"type": "closing", "title": "Cảm ơn", "subtitle": "Hỏi đáp"}
      ]
    }

Unknown keys are refused instead of ignored: a misspelled field is content the user asked for and would
otherwise disappear silently. The caps below are contract, not advice — the fix for an eighth bullet is a
second slide, and the renderer says so.
"""

from __future__ import annotations

import os

from . import theme

MAX_SLIDES = 40
MAX_BULLETS = 7
MAX_COLUMN_BULLETS = 6
MAX_AGENDA_ITEMS = 8
MAX_METRICS = 4
MIN_METRICS = 2
MAX_TABLE_COLUMNS = 6
MAX_TABLE_ROWS = 12
MAX_SOURCES = 30
MAX_TITLE_CHARS = 120
MAX_TEXT_CHARS = 400
MAX_BULLET_LEVEL = 1
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff")

DECK_KEYS = {"title", "subtitle", "author", "date", "confidentiality", "theme", "language", "footer",
             "sources", "slides"}
COMMON_SLIDE_KEYS = {"type", "note", "cites"}
SLIDE_KEYS: dict[str, tuple[set[str], set[str]]] = {
    # type: (required, optional)
    "title": (set(), {"title", "subtitle"}),
    "agenda": ({"items"}, {"title"}),
    "section": ({"title"}, {"subtitle"}),
    "bullets": ({"title", "bullets"}, {"lead"}),
    "columns": ({"title", "columns"}, {"lead"}),
    "metrics": ({"title", "metrics"}, {"lead"}),
    "table": ({"title", "columns", "rows"}, {"caption"}),
    "image": ({"title", "path"}, {"caption"}),
    "quote": ({"text"}, {"title", "attribution"}),
    "closing": ({"title"}, {"subtitle"}),
}


class PlanError(Exception):
    """Raised with every refusal found, so one render reports all of them."""

    def __init__(self, errors: list[str]) -> None:
        super().__init__("; ".join(errors))
        self.errors = errors


def _text(value: object, where: str, errors: list[str], limit: int = MAX_TEXT_CHARS) -> str:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{where} must be a non-empty string")
        return ""
    if len(value) > limit:
        errors.append(f"{where} is {len(value)} characters; at most {limit} fit a slide")
        return value[:limit]
    return value.strip()


def _keys(mapping: dict[str, object], allowed: set[str], required: set[str], where: str,
          errors: list[str]) -> None:
    for key in sorted(set(mapping) - allowed):
        errors.append(f"{where}: unknown field '{key}'; allowed: {', '.join(sorted(allowed))}")
    for key in sorted(required - set(mapping)):
        errors.append(f"{where}: missing required field '{key}'")


def _bullets(raw: object, where: str, errors: list[str], limit: int) -> list[dict[str, object]]:
    if not isinstance(raw, list) or not raw:
        errors.append(f"{where} must be a non-empty list")
        return []
    if len(raw) > limit:
        errors.append(f"{where} has {len(raw)} items; at most {limit} fit a slide — split it across slides")
    bullets: list[dict[str, object]] = []
    for index, item in enumerate(raw[:limit]):
        place = f"{where}[{index}]"
        if isinstance(item, str):
            bullets.append({"text": _text(item, place, errors), "level": 0})
            continue
        if not isinstance(item, dict):
            errors.append(f"{place} must be a string or {{text, level}}")
            continue
        _keys(item, {"text", "level"}, {"text"}, place, errors)
        level = item.get("level", 0)
        if not isinstance(level, int) or isinstance(level, bool) or not 0 <= level <= MAX_BULLET_LEVEL:
            errors.append(f"{place}.level must be 0 or {MAX_BULLET_LEVEL}")
            level = 0
        bullets.append({"text": _text(item.get("text"), f"{place}.text", errors), "level": level})
    return bullets


def _slide(raw: object, index: int, sources: int, errors: list[str]) -> dict[str, object] | None:
    where = f"slides[{index}]"
    if not isinstance(raw, dict):
        errors.append(f"{where} must be an object")
        return None
    kind = raw.get("type")
    if kind not in SLIDE_KEYS:
        errors.append(f"{where}: unknown type {kind!r}; allowed: {', '.join(sorted(SLIDE_KEYS))}")
        return None
    where = f"{where} ({kind})"
    required, optional = SLIDE_KEYS[kind]
    _keys(raw, required | optional | COMMON_SLIDE_KEYS, required, where, errors)

    slide: dict[str, object] = {"type": kind}
    if "title" in raw:
        slide["title"] = _text(raw["title"], f"{where}.title", errors, MAX_TITLE_CHARS)
    for field in ("subtitle", "lead", "caption", "attribution", "note", "text"):
        if field in raw:
            slide[field] = _text(raw[field], f"{where}.{field}", errors)

    cites = raw.get("cites", [])
    if not isinstance(cites, list):
        errors.append(f"{where}.cites must be a list of 1-based indexes into 'sources'")
    else:
        marks: list[int] = []
        for cite in cites:
            if not isinstance(cite, int) or isinstance(cite, bool) or not 1 <= cite <= max(sources, 0):
                errors.append(f"{where}.cites contains {cite!r}, which is not a 1-based index into the "
                              f"{sources} declared sources")
                continue
            marks.append(cite)
        slide["cites"] = marks

    if kind == "agenda":
        slide["items"] = [bullet["text"] for bullet in
                          _bullets(raw.get("items"), f"{where}.items", errors, MAX_AGENDA_ITEMS)]
    elif kind == "bullets":
        slide["bullets"] = _bullets(raw.get("bullets"), f"{where}.bullets", errors, MAX_BULLETS)
    elif kind == "columns":
        columns = raw.get("columns")
        if not isinstance(columns, list) or len(columns) != 2:
            errors.append(f"{where}.columns must be exactly 2 columns")
            columns = []
        parsed = []
        for position, column in enumerate(columns):
            place = f"{where}.columns[{position}]"
            if not isinstance(column, dict):
                errors.append(f"{place} must be an object {{heading, bullets}}")
                continue
            _keys(column, {"heading", "bullets"}, {"heading", "bullets"}, place, errors)
            parsed.append({"heading": _text(column.get("heading"), f"{place}.heading", errors, MAX_TITLE_CHARS),
                           "bullets": _bullets(column.get("bullets"), f"{place}.bullets", errors,
                                               MAX_COLUMN_BULLETS)})
        slide["columns"] = parsed
    elif kind == "metrics":
        metrics = raw.get("metrics")
        if not isinstance(metrics, list) or not MIN_METRICS <= len(metrics) <= MAX_METRICS:
            errors.append(f"{where}.metrics must have {MIN_METRICS} to {MAX_METRICS} items")
            metrics = []
        parsed = []
        for position, metric in enumerate(metrics):
            place = f"{where}.metrics[{position}]"
            if not isinstance(metric, dict):
                errors.append(f"{place} must be an object {{value, label, delta}}")
                continue
            _keys(metric, {"value", "label", "delta"}, {"value", "label"}, place, errors)
            parsed.append({"value": _text(metric.get("value"), f"{place}.value", errors, 24),
                           "label": _text(metric.get("label"), f"{place}.label", errors, 60),
                           "delta": _text(metric["delta"], f"{place}.delta", errors, 24)
                           if "delta" in metric else ""})
        slide["metrics"] = parsed
    elif kind == "table":
        columns = raw.get("columns")
        if not isinstance(columns, list) or not 1 <= len(columns) <= MAX_TABLE_COLUMNS:
            errors.append(f"{where}.columns must be 1 to {MAX_TABLE_COLUMNS} column names")
            columns = []
        header = [_text(column, f"{where}.columns[{position}]", errors, 60)
                  for position, column in enumerate(columns)]
        rows = raw.get("rows")
        if not isinstance(rows, list) or not rows:
            errors.append(f"{where}.rows must be a non-empty list of rows")
            rows = []
        if len(rows) > MAX_TABLE_ROWS:
            errors.append(f"{where}.rows has {len(rows)} rows; at most {MAX_TABLE_ROWS} fit a slide — "
                          "summarize or split the table")
        body = []
        for position, row in enumerate(rows[:MAX_TABLE_ROWS]):
            place = f"{where}.rows[{position}]"
            if not isinstance(row, list) or len(row) != len(header):
                errors.append(f"{place} must have {len(header)} cells, one per column")
                continue
            body.append([("" if cell is None else str(cell)).strip() for cell in row])
        slide["header"] = header
        slide["rows"] = body
    elif kind == "image":
        path = _text(raw.get("path"), f"{where}.path", errors, 500)
        if path:
            if not path.lower().endswith(IMAGE_SUFFIXES):
                errors.append(f"{where}.path must be an image file ({', '.join(IMAGE_SUFFIXES)})")
            elif not os.path.isfile(path):
                errors.append(f"{where}.path '{path}' does not exist; save the image before rendering")
        slide["path"] = path
    return slide


def validate(raw: object) -> dict[str, object]:
    """The normalized plan, or PlanError carrying every refusal."""
    errors: list[str] = []
    if not isinstance(raw, dict):
        raise PlanError(["the plan must be a JSON object"])
    _keys(raw, DECK_KEYS, {"title", "slides"}, "plan", errors)

    deck: dict[str, object] = {"title": _text(raw.get("title"), "plan.title", errors, MAX_TITLE_CHARS)}
    for field in ("subtitle", "author", "date", "confidentiality", "footer"):
        deck[field] = _text(raw[field], f"plan.{field}", errors, MAX_TITLE_CHARS) if field in raw else ""

    name = raw.get("theme", theme.DEFAULT_THEME)
    if name not in theme.PALETTES:
        errors.append(f"plan.theme {name!r} is unknown; allowed: {', '.join(sorted(theme.PALETTES))}")
        name = theme.DEFAULT_THEME
    deck["theme"] = name
    language = raw.get("language", theme.DEFAULT_LANGUAGE)
    if language not in theme.LABELS:
        errors.append(f"plan.language {language!r} is unknown; allowed: {', '.join(sorted(theme.LABELS))}")
        language = theme.DEFAULT_LANGUAGE
    deck["language"] = language

    raw_sources = raw.get("sources", [])
    sources: list[dict[str, str]] = []
    if not isinstance(raw_sources, list):
        errors.append("plan.sources must be a list of {label, detail}")
        raw_sources = []
    if len(raw_sources) > MAX_SOURCES:
        errors.append(f"plan.sources has {len(raw_sources)} entries; at most {MAX_SOURCES} are rendered")
    for index, source in enumerate(raw_sources[:MAX_SOURCES]):
        place = f"plan.sources[{index}]"
        if isinstance(source, str):
            sources.append({"label": _text(source, place, errors, MAX_TITLE_CHARS), "detail": ""})
            continue
        if not isinstance(source, dict):
            errors.append(f"{place} must be a string or {{label, detail}}")
            continue
        _keys(source, {"label", "detail"}, {"label"}, place, errors)
        sources.append({"label": _text(source.get("label"), f"{place}.label", errors, MAX_TITLE_CHARS),
                        "detail": _text(source["detail"], f"{place}.detail", errors, MAX_TITLE_CHARS)
                        if "detail" in source else ""})
    deck["sources"] = sources

    raw_slides = raw.get("slides")
    if not isinstance(raw_slides, list) or not raw_slides:
        errors.append("plan.slides must be a non-empty list")
        raw_slides = []
    if len(raw_slides) > MAX_SLIDES:
        errors.append(f"plan.slides has {len(raw_slides)} slides; at most {MAX_SLIDES} are rendered")
    slides = [slide for index, slide in
              ((index, _slide(entry, index, len(sources), errors))
               for index, entry in enumerate(raw_slides[:MAX_SLIDES])) if slide is not None]
    deck["slides"] = slides

    if errors:
        raise PlanError(errors)
    return deck
