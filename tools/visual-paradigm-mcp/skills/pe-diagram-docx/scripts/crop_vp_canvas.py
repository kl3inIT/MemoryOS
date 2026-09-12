#!/usr/bin/env python3
"""Crop a clean diagram image from a Visual Paradigm window screenshot."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageChops


def parse_box(value: str) -> tuple[int, int, int, int]:
    parts = [int(item.strip()) for item in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("canvas must be x,y,width,height")
    x, y, width, height = parts
    if min(width, height) <= 0:
        raise argparse.ArgumentTypeError("width and height must be positive")
    return x, y, x + width, y + height


def trim_near_white(image: Image.Image, tolerance: int, padding: int) -> Image.Image:
    rgb = image.convert("RGB")
    background = Image.new("RGB", rgb.size, (255, 255, 255))
    difference = ImageChops.difference(rgb, background).convert("L")
    threshold = difference.point(lambda px: 255 if px > tolerance else 0)
    bbox = threshold.getbbox()
    if bbox is None:
        return rgb
    left, top, right, bottom = bbox
    return rgb.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(rgb.width, right + padding),
            min(rgb.height, bottom + padding),
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--canvas", required=True, type=parse_box)
    parser.add_argument("--trim", action="store_true")
    parser.add_argument("--padding", type=int, default=24)
    parser.add_argument("--tolerance", type=int, default=12)
    args = parser.parse_args()

    image = Image.open(args.input)
    cropped = image.crop(args.canvas)
    if args.trim:
        cropped = trim_near_white(cropped, args.tolerance, args.padding)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    cropped.save(args.output)
    print(f"{args.output} ({cropped.width}x{cropped.height})")


if __name__ == "__main__":
    main()
