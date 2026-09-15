"""MemoryOS executor additions (MEM-110): packages, CLI tools and Vietnamese PDF output."""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

from memoryos_interpreter.main import create_app

VIETNAMESE = "Báo cáo doanh thu quý 3 — Hà Nội, Đà Nẵng"


def _execute(client: TestClient, code: str) -> dict[str, object]:
    response = client.post("/v1/execute", json={"code": code, "timeout_ms": 30000})
    assert response.status_code == 200
    payload: dict[str, object] = response.json()
    assert payload["exit_code"] == 0, payload
    assert payload["stderr"] == "", payload
    return payload


def test_added_packages_and_cli_tools_are_available() -> None:
    client = TestClient(create_app())
    code = """
import importlib, json, shutil
modules = ['statsmodels', 'pyarrow', 'xlrd', 'xlsxwriter', 'chardet', 'charset_normalizer',
           'tabulate', 'jinja2', 'markdown', 'bs4', 'markitdown', 'pdf2image', 'sympy']
for name in modules:
    importlib.import_module(name)
tools = ['pdftoppm', 'pdftotext', 'qpdf', 'sqlite3', 'unzip', 'zip']
print(json.dumps({'missing_tools': [t for t in tools if shutil.which(t) is None]}))
""".strip()

    payload = _execute(client, code)

    assert json.loads(str(payload["stdout"])) == {"missing_tools": []}


def test_vietnamese_pdf_with_registered_font_round_trips() -> None:
    client = TestClient(create_app())
    code = f"""
from fpdf import FPDF
from pypdf import PdfReader
pdf = FPDF()
pdf.add_page()
pdf.add_font('DejaVu', fname='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
pdf.set_font('DejaVu', size=14)
pdf.cell(text={VIETNAMESE!r})
pdf.output('report.pdf')
print(PdfReader('report.pdf').pages[0].extract_text().strip())
""".strip()

    payload = _execute(client, code)

    assert payload["stdout"] == VIETNAMESE + "\n"
    files = payload["files"]
    assert isinstance(files, list)
    file_id = next(f["file_id"] for f in files if f["path"] == "report.pdf")
    download = client.get(f"/v1/files/{file_id}")
    assert download.status_code == 200
    assert download.content.startswith(b"%PDF-")
