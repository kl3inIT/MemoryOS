"""Pixel-derived orientation decisions; no financial labels or PDF-angle inference."""

import io
import math
import subprocess
import time
from dataclasses import dataclass

from PIL import Image

MAX_EDGE = 1600
MAX_PROCESS_SECONDS = 5.0
REVISION = "consensus-osd-v1"


@dataclass(frozen=True)
class OrientationDecision:
    clockwise: int | None
    reason: str


def _detect(image: Image.Image, deadline: float) -> int | None:
    """Return Tesseract's clockwise orientation within the shared deadline."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return None
    encoded = io.BytesIO()
    image.save(encoded, format="PNG")
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return None
    try:
        result = subprocess.run(
            ["tesseract", "stdin", "stdout", "--psm", "0", "-l", "osd", "--dpi", "150"],
            input=encoded.getvalue(),
            capture_output=True,
            timeout=min(MAX_PROCESS_SECONDS, remaining),
            check=False,
        )
    except subprocess.TimeoutExpired:
        return None
    if result.returncode != 0:
        return None
    fields = dict(
        line.split(":", 1)
        for line in result.stdout.decode("utf-8", errors="replace").splitlines()
        if ":" in line
    )
    try:
        angle = int(fields["Rotate"])
        confidence = float(fields["Orientation confidence"])
    except (KeyError, ValueError):
        return None
    if (
        angle not in (0, 90, 180, 270)
        or not math.isfinite(confidence)
        or confidence < 0
    ):
        return None
    return angle


def decide_orientation(image: Image.Image, deadline: float) -> OrientationDecision:
    """Accept an orientation only when both rotated image halves agree."""
    if image.width > MAX_EDGE or image.height > MAX_EDGE:
        raise ValueError("Orientation raster exceeds the pixel bound")
    angle = _detect(image, deadline)
    if angle is None:
        return OrientationDecision(None, "OSD_UNRESOLVED")
    if angle == 0:
        return OrientationDecision(0, "UNCHANGED")
    with image.rotate(-angle, expand=True) as upright:
        middle = upright.width // 2
        if middle == 0:
            return OrientationDecision(None, "INSUFFICIENT_CONSENSUS")
        for bounds in (
            (0, 0, middle, upright.height),
            (middle, 0, upright.width, upright.height),
        ):
            with upright.crop(bounds) as region:
                if _detect(region, deadline) != 0:
                    return OrientationDecision(None, "INSUFFICIENT_CONSENSUS")
    return OrientationDecision(angle, "ROTATED")
