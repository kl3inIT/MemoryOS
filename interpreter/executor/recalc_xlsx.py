#!/opt/executor-venv/bin/python
"""Recalculate .xlsx formulas with LibreOffice and report cells that still error (MEM-110).

openpyxl writes a formula without a cached value and xlsxwriter caches 0, so a workbook built
in the sandbox shows empty or zero results in any reader that does not recalculate. The
Anthropic xlsx skill solves the same gap with LibreOffice (``scripts/recalc.py``); this helper
converts in place instead of running a macro.

Usage: recalc-xlsx FILE.xlsx [FILE.xlsx ...]
Prints one JSON object per file: {"file", "recalculated", "error_count", "errors"}.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import openpyxl

TIMEOUT_SEC = 25
MAX_REPORTED_ERRORS = 20
ERROR_VALUES = {"#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A", "#GETTING_DATA"}
# LibreOffice keeps cached .xlsx values by default ("never recalculate"); force recalculation.
PROFILE_SETTINGS = """<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry"
 xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<item oor:path="/org.openoffice.Office.Calc/Formula/Load">
<prop oor:name="OOXMLRecalcMode" oor:op="fuse"><value>0</value></prop>
</item>
</oor:items>
"""


def _errors(path: Path) -> list[str]:
    errors = []
    workbook = openpyxl.load_workbook(path, data_only=True, read_only=True)
    try:
        for sheet in workbook.worksheets:
            for row in sheet.iter_rows():
                for cell in row:
                    if isinstance(cell.value, str) and cell.value in ERROR_VALUES:
                        errors.append(f"{sheet.title}!{cell.coordinate}: {cell.value}")
    finally:
        workbook.close()
    return errors


def recalculate(paths: list[Path]) -> list[dict[str, object]]:
    """Converts every eligible workbook in one LibreOffice start, which dominates the run time."""
    reports: dict[Path, dict[str, object]] = {}
    eligible: list[Path] = []
    for path in paths:
        if path.suffix.lower() != ".xlsx":
            # Converting .xlsm or .xls to .xlsx would silently drop macros or change the format.
            reports[path] = {
                "file": str(path),
                "recalculated": False,
                "error": "only .xlsx files are supported",
            }
        elif not path.is_file():
            reports[path] = {"file": str(path), "recalculated": False, "error": "file not found"}
        elif any(other.name == path.name for other in eligible):
            reports[path] = {
                "file": str(path),
                "recalculated": False,
                "error": "run separately: same file name as another argument",
            }
        else:
            eligible.append(path)
    if eligible:
        with tempfile.TemporaryDirectory(prefix="recalc-", dir="/tmp") as work:
            profile = Path(work, "profile")
            (profile / "user").mkdir(parents=True)
            (profile / "user" / "registrymodifications.xcu").write_text(
                PROFILE_SETTINGS, encoding="utf-8"
            )
            out = Path(work, "out")
            failure = None
            try:
                subprocess.run(
                    [
                        "soffice",
                        f"-env:UserInstallation={profile.as_uri()}",
                        "--headless",
                        "--norestore",
                        "--convert-to",
                        "xlsx",
                        "--outdir",
                        str(out),
                        *map(str, eligible),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=TIMEOUT_SEC,
                    check=False,
                )
            except subprocess.TimeoutExpired:
                failure = f"LibreOffice timed out after {TIMEOUT_SEC} s"
            for path in eligible:
                converted = out / path.name
                if failure is None and converted.is_file():
                    shutil.copyfile(converted, path)
                    errors = _errors(path)
                    reports[path] = {
                        "file": str(path),
                        "recalculated": True,
                        "error_count": len(errors),
                        "errors": errors[:MAX_REPORTED_ERRORS],
                    }
                else:
                    reports[path] = {
                        "file": str(path),
                        "recalculated": False,
                        "error": failure or "LibreOffice could not open the workbook",
                    }
    return [reports[path] for path in paths]


def main(arguments: list[str]) -> int:
    if not arguments:
        print("usage: recalc-xlsx FILE.xlsx [FILE.xlsx ...]", file=sys.stderr)
        return 2
    reports = recalculate([Path(argument) for argument in arguments])
    for report in reports:
        print(json.dumps(report, ensure_ascii=False))
    return 0 if all(report["recalculated"] for report in reports) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
