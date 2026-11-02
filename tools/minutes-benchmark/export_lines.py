"""Writes each copied meeting's transcript as the provider numbers it, for drafting and reviewing the answer key.

Runs in the promptfoo container as the `minutes` role:

    python3 export_lines.py /workspace/minutes/copies.json /workspace/minutes/key/lines

One `NN.json` per meeting, in the order of `copies.json`: the copy id, title, kind, participants, speakers and
numbered lines.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "promptfoo"))

from minutes_provider import _api, minutes  # noqa: E402


def main() -> None:
    copies = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["copies"]
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    api = _api()
    for number, entry in enumerate(copies.values(), 1):
        detail = api.get(f"/api/meetings/{entry['copy']}")
        shaped = minutes(detail)
        record = {
            "copy": entry["copy"],
            "title": detail.get("title"),
            "kind": detail.get("kind"),
            "participants": detail.get("participants") or [],
            "speakers": shaped["speakers"],
            "lines": shaped["lines"],
        }
        (out / f"{number:02}.json").write_text(json.dumps(record, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{number:02} {len(record['lines'])} lines")


if __name__ == "__main__":
    main()
