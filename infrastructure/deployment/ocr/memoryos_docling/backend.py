"""Normalize scanned pages inside the pipeline producer, before native layout parsing."""

import math
import tempfile
import threading
import time
from collections.abc import Iterator
from io import BytesIO
from pathlib import Path
from typing import Literal

import pypdfium2 as pdfium
from docling.backend.docling_parse_backend import (
    DoclingParseDocumentBackend,
    ThreadedDoclingParseDocumentBackend,
)
from docling.backend.pdf_backend import PdfDocumentBackend, PdfPageBackend
from docling.backend.pypdfium2_backend import PyPdfiumDocumentBackend
from docling.datamodel.backend_options import PdfBackendOptions
from docling.datamodel.document import InputDocument
from docling.utils.locks import pypdfium2_lock
from docling.utils.pdf_outline import extract_outline_from_pdfium

from .orientation import MAX_EDGE, REVISION, decide_orientation

BACKENDS: dict[Literal["pdfium", "parse", "threaded"], type[PdfDocumentBackend]] = {
    "pdfium": PyPdfiumDocumentBackend,
    "parse": DoclingParseDocumentBackend,
    "threaded": ThreadedDoclingParseDocumentBackend,
}


class OrientationBackendOptions(PdfBackendOptions):
    delegate_kind: Literal["pdfium", "parse", "threaded"]
    delegate_options: PdfBackendOptions | None = None
    preprocessing_seconds: float = 30.0


class OrientationBackend(PdfDocumentBackend):
    def __init__(
        self,
        in_doc: InputDocument,
        path_or_stream: BytesIO | Path,
        options: OrientationBackendOptions,
    ) -> None:
        """Open the source PDF and prepare bounded orientation state."""
        super().__init__(in_doc, path_or_stream, options)
        self._orientation_options = options
        self._input = in_doc
        self._delegate_type = BACKENDS[options.delegate_kind]
        self._delegate: PdfDocumentBackend | None = None
        self._temporary: tempfile.TemporaryDirectory | None = None
        self._lock = threading.RLock()
        self._closed = threading.Event()
        self.orientations: dict[int, dict] = {}
        password = (
            options.delegate_options.password if options.delegate_options else None
        )
        self._password = password.get_secret_value() if password else None
        with (
            pypdfium2_lock,
            pdfium.PdfDocument(path_or_stream, password=self._password) as document,
        ):
            self._count = len(document)

    def page_count(self) -> int:
        """Return the number of pages in the source PDF."""
        return self._count

    def is_valid(self) -> bool:
        """Return whether the backend has pages and remains open."""
        return self._count > 0 and not self._closed.is_set()

    def get_document_outline(self) -> list:
        """Extract the outline from the unchanged source PDF."""
        with pypdfium2_lock:
            document = pdfium.PdfDocument(self.path_or_stream, password=self._password)
        try:
            return extract_outline_from_pdfium(document)
        finally:
            with pypdfium2_lock:
                document.close()

    def _prepare_source(self) -> BytesIO | Path:
        """Return the source or a temporary PDF with accepted page rotations."""
        source = self.path_or_stream
        if source is None:
            raise RuntimeError("PDF backend is closed")
        deadline = time.monotonic() + min(
            30.0, self._orientation_options.preprocessing_seconds
        )
        first, last = self._input.limits.page_range
        changed = False
        with pypdfium2_lock:
            document = pdfium.PdfDocument(source, password=self._password)
        try:
            for index in range(max(0, first - 1), min(self._count, last)):
                record = {"revision": REVISION, "clockwise_degrees": None}
                self.orientations[index + 1] = record
                if self._closed.is_set() or time.monotonic() >= deadline:
                    record["reason"] = "BUDGET_EXHAUSTED"
                    continue
                with pypdfium2_lock:
                    page = document[index]
                try:
                    with pypdfium2_lock:
                        width, height = page.get_size()
                        original_rotation = page.get_rotation()
                        text = page.get_textpage()
                        try:
                            native_text = text.count_chars() > 0
                        finally:
                            text.close()
                    record.update(
                        original_size={"width": width, "height": height},
                        original_pdf_rotation=original_rotation,
                    )
                    if native_text:
                        record.update(reason="NATIVE_TEXT", clockwise_degrees=0)
                        continue
                    if not all(
                        math.isfinite(value) and value > 0 for value in (width, height)
                    ):
                        record["reason"] = "INVALID_GEOMETRY"
                        continue
                    with pypdfium2_lock:
                        bitmap = page.render(
                            scale=min(2.5, MAX_EDGE / max(width, height))
                        )
                        try:
                            image = bitmap.to_pil().copy()
                        finally:
                            bitmap.close()
                    try:
                        image.thumbnail((MAX_EDGE, MAX_EDGE))
                        decision = decide_orientation(image, deadline)
                    finally:
                        image.close()
                    record.update(
                        reason=decision.reason, clockwise_degrees=decision.clockwise
                    )
                    if decision.clockwise:
                        with pypdfium2_lock:
                            page.set_rotation(
                                (original_rotation + decision.clockwise) % 360
                            )
                        changed = True
                finally:
                    with pypdfium2_lock:
                        page.close()
            if not changed or self._closed.is_set():
                return source
            self._temporary = tempfile.TemporaryDirectory(
                prefix="memoryos-orientation-"
            )
            normalized = Path(self._temporary.name) / "normalized.pdf"
            with pypdfium2_lock:
                document.save(normalized)
            if normalized.stat().st_size > self._input.limits.max_file_size:
                self._temporary.cleanup()
                self._temporary = None
                for record in self.orientations.values():
                    if record.get("reason") == "ROTATED":
                        record.update(
                            reason="NORMALIZED_FILE_LIMIT", clockwise_degrees=None
                        )
                return source
            return normalized
        finally:
            with pypdfium2_lock:
                document.close()

    def _get_delegate(self) -> PdfDocumentBackend:
        """Create or return the backend that parses the prepared PDF."""
        with self._lock:
            if self._closed.is_set():
                raise RuntimeError("PDF backend is closed")
            if self._delegate is None:
                source = self._prepare_source()
                if self._closed.is_set():
                    raise RuntimeError("PDF preprocessing was cancelled")
                self._delegate = self._delegate_type(
                    self._input, source, self._orientation_options.delegate_options
                )
            return self._delegate

    def load_page(self, page_no: int) -> PdfPageBackend:
        """Load a page from the prepared delegate backend."""
        return self._get_delegate().load_page(page_no)

    def iter_pages(self) -> Iterator[PdfPageBackend]:
        """Iterate over pages from the prepared delegate backend."""
        yield from self._get_delegate().iter_pages()

    def unload(self) -> None:
        """Close the delegate and remove any normalized temporary PDF."""
        self._closed.set()
        with self._lock:
            try:
                if self._delegate is not None:
                    self._delegate.unload()
                    self._delegate = None
            finally:
                if self._temporary is not None:
                    self._temporary.cleanup()
                    self._temporary = None
                super().unload()


class SequentialOrientationBackend(OrientationBackend):
    supports_random_page_access = False
