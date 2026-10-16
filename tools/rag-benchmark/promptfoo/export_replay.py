"""Writes a past eval's replies as the replay file `replay_provider.py` reads: {"<id>|<role>": output}.
Runs where promptfoo keeps its database.

    python export_replay.py <eval id> <out.json> [path/to/promptfoo.db]
"""

from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

DATABASE = Path.home() / ".promptfoo" / "promptfoo.db"


def main() -> None:
    eval_id, out = sys.argv[1], Path(sys.argv[2])
    database = Path(sys.argv[3]) if len(sys.argv) > 3 else DATABASE
    db = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    replies: dict[str, dict] = {}
    rows = db.execute(
        "select metadata, response from eval_results where eval_id = ? order by test_idx",
        (eval_id,),
    )
    for metadata, response in rows:
        output = (json.loads(response or "{}") or {}).get("output")
        if isinstance(output, dict):
            meta = json.loads(metadata or "{}")
            replies.setdefault(f"{meta['id']}|{meta['role']}", output)
    out.write_text(json.dumps(replies, ensure_ascii=False), encoding="utf-8")
    print(f"{len(replies)} replies into {out}")


if __name__ == "__main__":
    main()
