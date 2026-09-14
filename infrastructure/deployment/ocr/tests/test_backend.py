import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from docling.datamodel.base_models import InputFormat
from docling.datamodel.document import InputDocument
from memoryos_docling.backend import OrientationBackend, OrientationBackendOptions
from memoryos_docling.orientation import OrientationDecision
from PIL import Image


class BackendTests(unittest.TestCase):
    def test_confirmed_rotation_changes_layout_coordinates_not_source_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "scan.pdf"
            with Image.new("RGB", (120, 80), "white") as image:
                image.save(source, "PDF", resolution=72)
            original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
            document = InputDocument(
                path_or_stream=source,
                format=InputFormat.PDF,
                backend=OrientationBackend,
                backend_options=OrientationBackendOptions(delegate_kind="pdfium"),
            )
            backend = document._backend
            page = None
            try:
                with patch(
                    "memoryos_docling.backend.decide_orientation",
                    return_value=OrientationDecision(90, "ROTATED"),
                ):
                    page = backend.load_page(0)
                size = page.get_size()
                self.assertEqual((80, 120), (size.width, size.height))
                self.assertEqual(
                    original_hash, hashlib.sha256(source.read_bytes()).hexdigest()
                )
                self.assertEqual(90, backend.orientations[1]["clockwise_degrees"])
            finally:
                if page is not None:
                    page.unload()
                backend.unload()
            with self.assertRaises(RuntimeError):
                backend.load_page(0)

    def test_unresolved_orientation_preserves_page_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "scan.pdf"
            with Image.new("RGB", (120, 80), "white") as image:
                image.save(source, "PDF", resolution=72)
            document = InputDocument(
                path_or_stream=source,
                format=InputFormat.PDF,
                backend=OrientationBackend,
                backend_options=OrientationBackendOptions(delegate_kind="pdfium"),
            )
            backend = document._backend
            page = None
            try:
                with patch(
                    "memoryos_docling.backend.decide_orientation",
                    return_value=OrientationDecision(None, "OSD_UNRESOLVED"),
                ):
                    page = backend.load_page(0)
                size = page.get_size()
                self.assertEqual((120, 80), (size.width, size.height))
            finally:
                if page is not None:
                    page.unload()
                backend.unload()


if __name__ == "__main__":
    unittest.main()
