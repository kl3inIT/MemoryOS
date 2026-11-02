"""Writes the promptfoo config of a minutes benchmark run: one test per copied meeting with a reviewed key.

    python3 build_config.py --copies /workspace/minutes/copies.json --keys /workspace/minutes/key \
        --label "<what this run measures>" --out /tmp/minutes.yaml [--only 04 07]

promptfoo reads YAML and JSON alike; the file is written as JSON. Run it with `--repeat 3`, since the model's
minutes vary from one writing to the next.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--copies", type=Path, required=True)
    parser.add_argument("--keys", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", nargs="*", default=[])
    args = parser.parse_args()

    copies = json.loads(args.copies.read_text(encoding="utf-8"))
    # A meeting recorded twice is kept once, so its errors are not counted twice; numbers stay as copied.
    dropped = set(copies.get("dropped") or {})
    tests = []
    for number, entry in enumerate(copies["copies"].values(), 1):
        name = f"{number:02}"
        key = args.keys / f"{name}.json"
        if name in dropped or (args.only and name not in args.only):
            continue
        if not key.exists():
            continue
        tests.append({"description": f"meeting {name}", "vars": {"copy": entry["copy"], "key": str(key)}})
    here = Path(__file__).resolve().parent
    config = {
        "description": f"Minutes benchmark: {args.label} (corpus {copies['fingerprint'][:12]})",
        "prompts": ["{{copy}}"],
        "providers": [{"id": f"file://{here / 'minutes_provider.py'}", "label": args.label}],
        "defaultTest": {"assert": [{"type": "python", "value": f"file://{here / 'assertions.py'}:score"}]},
        "tests": tests,
    }
    args.out.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    print(f"{len(tests)} meetings -> {args.out}")


if __name__ == "__main__":
    main()
