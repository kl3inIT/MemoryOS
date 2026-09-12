#!/usr/bin/env python3
"""Create several PE DOCX files from a shared or per-job manifest."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else (base / path).resolve()


def run_job(
    index: int,
    job: dict,
    base: Path,
    default_template: str | None,
    default_manifest: str | None,
    temp_dir: Path,
) -> Path:
    template_value = job.get("template", default_template)
    manifest_value = job.get("manifest", default_manifest)
    if not template_value or not manifest_value or not job.get("output"):
        raise ValueError(f"job {index} requires template, manifest, and output")

    template = resolve(base, template_value)
    manifest = resolve(base, manifest_value)
    output = resolve(base, job["output"])
    variables_path = temp_dir / f"variables-{index}.json"
    variables_path.write_text(
        json.dumps(job.get("variables", {}), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    command = [
        sys.executable,
        str(Path(__file__).with_name("fill_pe_docx.py")),
        "--template",
        str(template),
        "--manifest",
        str(manifest),
        "--variables",
        str(variables_path),
        "--output",
        str(output),
    ]
    subprocess.run(command, check=True)
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", required=True, type=Path)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    batch_path = args.batch.resolve()
    batch = json.loads(batch_path.read_text(encoding="utf-8"))
    jobs = batch.get("jobs", [])
    if not jobs:
        raise ValueError("batch file contains no jobs")

    workers = max(1, min(args.workers, len(jobs)))
    with tempfile.TemporaryDirectory(prefix="pe-docx-batch-") as temp:
        temp_dir = Path(temp)
        futures = []
        with ThreadPoolExecutor(max_workers=workers) as executor:
            for index, job in enumerate(jobs, start=1):
                futures.append(
                    executor.submit(
                        run_job,
                        index,
                        job,
                        batch_path.parent,
                        batch.get("template"),
                        batch.get("manifest"),
                        temp_dir,
                    )
                )
            outputs = [future.result() for future in as_completed(futures)]

    for output in sorted(outputs):
        print(output)


if __name__ == "__main__":
    main()
