import subprocess
import time
import unittest
from unittest.mock import patch

from memoryos_docling.orientation import decide_orientation
from PIL import Image


def osd(angle, confidence="1.5"):
    return subprocess.CompletedProcess(
        [], 0, f"Rotate: {angle}\nOrientation confidence: {confidence}\n".encode(), b""
    )


class OrientationTests(unittest.TestCase):
    def setUp(self):
        self.image = Image.new("RGB", (120, 80), "white")
        self.addCleanup(self.image.close)
        self.deadline = time.monotonic() + 30

    def test_rotation_requires_independent_upright_regions_without_mutating_input(self):
        before = self.image.tobytes()
        with patch(
            "memoryos_docling.orientation.subprocess.run",
            side_effect=[osd(90), osd(0), osd(0)],
        ):
            result = decide_orientation(self.image, self.deadline)
        self.assertEqual(90, result.clockwise)
        self.assertEqual((120, 80), self.image.size)
        self.assertEqual(before, self.image.tobytes())

    def test_disagreeing_region_abstains(self):
        with patch(
            "memoryos_docling.orientation.subprocess.run",
            side_effect=[osd(90), osd(0), osd(180)],
        ):
            result = decide_orientation(self.image, self.deadline)
        self.assertIsNone(result.clockwise)

    def test_missing_region_evidence_abstains(self):
        with patch(
            "memoryos_docling.orientation.subprocess.run",
            side_effect=[
                osd(90),
                osd(0),
                subprocess.CompletedProcess([], 1, b"", b"Too few characters"),
            ],
        ):
            result = decide_orientation(self.image, self.deadline)
        self.assertIsNone(result.clockwise)

    def test_expired_budget_does_not_launch_a_process(self):
        with patch("memoryos_docling.orientation.subprocess.run") as run:
            result = decide_orientation(self.image, time.monotonic() - 1)
        self.assertIsNone(result.clockwise)
        run.assert_not_called()

    def test_process_timeout_abstains(self):
        with patch(
            "memoryos_docling.orientation.subprocess.run",
            side_effect=subprocess.TimeoutExpired("tesseract", 5),
        ):
            result = decide_orientation(self.image, self.deadline)
        self.assertIsNone(result.clockwise)

    def test_nonfinite_detector_output_abstains(self):
        with patch(
            "memoryos_docling.orientation.subprocess.run", return_value=osd(90, "nan")
        ):
            result = decide_orientation(self.image, self.deadline)
        self.assertIsNone(result.clockwise)

    def test_oversized_raster_is_rejected_before_subprocess_work(self):
        with Image.new("RGB", (1601, 1)) as oversized:
            with (
                patch("memoryos_docling.orientation.subprocess.run") as run,
                self.assertRaises(ValueError),
            ):
                decide_orientation(oversized, self.deadline)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
