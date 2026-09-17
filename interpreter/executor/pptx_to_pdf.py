#!/opt/executor-venv/bin/python
"""Convert one .pptx to PDF for the Chat preview (MEM-111), inside the network-less executor.

Onyx Craft previews decks with LibreOffice in its sandbox (``generate_pptx_preview``); LibreChat hardens the same
conversion with a throwaway profile, a timeout and output caps. The API never opens the deck itself.

Usage: pptx-to-pdf DECK.pptx OUTPUT.pdf
Prints one JSON object: {"converted": true, "pages": n} or {"converted": false, "error": "..."}.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

TIMEOUT_SEC = 45
MAX_PDF_BYTES = 25 * 1024 * 1024
MAX_PAGES = 300


def _pages(pdf: Path) -> int | None:
    try:
        info = subprocess.run(
            ["pdfinfo", str(pdf)], capture_output=True, text=True, timeout=10, check=False
        )
    except subprocess.TimeoutExpired:
        return None
    match = re.search(r"^Pages:\s+(\d+)", info.stdout, re.MULTILINE)
    return int(match.group(1)) if match else None


def convert(deck: Path, output: Path) -> dict[str, object]:
    if deck.suffix.lower() != ".pptx":
        return {"converted": False, "error": "only .pptx files are supported"}
    if not deck.is_file():
        return {"converted": False, "error": "file not found"}
    with tempfile.TemporaryDirectory(prefix="pptx-", dir="/tmp") as work:
        profile = Path(work, "profile")
        source = Path(work, "deck.pptx")  # A fixed ASCII name avoids LibreOffice's handling of odd file names.
        shutil.copyfile(deck, source)
        out = Path(work, "out")
        try:
            subprocess.run(
                [
                    "soffice",
                    f"-env:UserInstallation={profile.as_uri()}",
                    "--headless",
                    "--norestore",
                    "--convert-to",
                    "pdf",
                    "--outdir",
                    str(out),
                    str(source),
                ],
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SEC,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return {"converted": False, "error": f"LibreOffice timed out after {TIMEOUT_SEC} s"}
        pdf = out / "deck.pdf"
        if not pdf.is_file():
            return {"converted": False, "error": "LibreOffice could not open the presentation"}
        if pdf.stat().st_size > MAX_PDF_BYTES:
            return {"converted": False, "error": "the preview PDF is too large"}
        pages = _pages(pdf)
        if pages is None or pages > MAX_PAGES:
            return {"converted": False, "error": f"the preview must have 1 to {MAX_PAGES} pages"}
        shutil.copyfile(pdf, output)
        return {"converted": True, "pages": pages}


def main(arguments: list[str]) -> int:
    if len(arguments) != 2:
        print("usage: pptx-to-pdf DECK.pptx OUTPUT.pdf", file=sys.stderr)
        return 2
    report = convert(Path(arguments[0]), Path(arguments[1]))
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["converted"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
