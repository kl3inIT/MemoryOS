#!/usr/bin/env python3
"""Render a DOCX through Microsoft Word and rasterize every PDF page."""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess

import fitz


def ps_quote(value: Path) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def export_with_word(source: Path, pdf_path: Path) -> None:
    script = f"""
$ErrorActionPreference = 'Stop'
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {{
  $document = $word.Documents.Open({ps_quote(source)}, $false, $true)
  try {{
    $document.ExportAsFixedFormat({ps_quote(pdf_path)}, 17)
  }} finally {{
    $document.Close($false)
  }}
}} finally {{
  $word.Quit()
  [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
}}
"""
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--dpi", type=int, default=144)
    args = parser.parse_args()

    source = args.input.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    pdf_path = output_dir / f"{source.stem}.pdf"

    export_with_word(source, pdf_path)

    scale = args.dpi / 72
    pdf = fitz.open(pdf_path)
    page_count = len(pdf)
    try:
        for page_number, page in enumerate(pdf, start=1):
            pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            pixmap.save(output_dir / f"page-{page_number}.png")
    finally:
        pdf.close()

    print(f"{pdf_path} ({page_count} pages)")


if __name__ == "__main__":
    main()
