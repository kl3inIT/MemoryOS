"""Copies the benchmark's meetings into the Minutes Benchmark account, once each.

Runs on the staging host, which reaches PostgreSQL through `docker exec memoryos-postgres psql`. MemoryOS has
no way to create a meeting from a transcript, so the rows are copied in one transaction per meeting: the
meeting (ended, minutes not yet written), its speakers with the names they had, and its utterances in order.
A copy's id is derived from its source's, so a second run finds it and copies nothing.

    python3 copy.py --sources /apps/memoryos/promptfoo/minutes/sources.json \
        --actor <Minutes Benchmark actor id> --out /apps/memoryos/promptfoo/minutes/copies.json

`sources.json` lists the source meeting ids. `copies.json` maps each source to its copy and records the
fingerprint of the copies' transcripts; results on another fingerprint are not comparable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import uuid
from pathlib import Path

# Fixed so a source always maps to the same copy.
NAMESPACE = uuid.UUID("5d2f2a8e-3b7c-4c1e-9f61-0c5b7a4e9d13")
PSQL = ["docker", "exec", "-i", "memoryos-postgres", "psql", "-U", "memoryos_platform", "-d", "memoryos",
        "-v", "ON_ERROR_STOP=1", "-At", "-q"]

COPY = """
BEGIN;
INSERT INTO meeting(tenant_id, id, owner_actor_id, title, kind, language, participants, terms, notes, status,
                    provider, model, diarized, created_at, ended_at, minutes_status)
SELECT tenant_id, :'copy', :'actor', left('Benchmark - ' || title, 200), kind, language, participants, terms, '',
       'ENDED', provider, model, diarized, created_at, coalesce(ended_at, created_at), 'NONE'
FROM meeting WHERE id = :'source' AND status = 'ENDED'
  AND NOT EXISTS (SELECT 1 FROM meeting WHERE id = :'copy');
INSERT INTO meeting_speaker(tenant_id, meeting_id, track, label, name, suggestion_dismissed)
SELECT s.tenant_id, :'copy', s.track, s.label, s.name, s.suggestion_dismissed
FROM meeting_speaker s WHERE s.meeting_id = :'source'
ON CONFLICT DO NOTHING;
INSERT INTO meeting_utterance(tenant_id, id, meeting_id, track, speaker, start_ms, end_ms, text, confidence,
                              created_at, spans, edit_source)
SELECT u.tenant_id, gen_random_uuid(), :'copy', u.track, u.speaker, u.start_ms, u.end_ms, u.text, u.confidence,
       u.created_at, u.spans, u.edit_source
FROM meeting_utterance u WHERE u.meeting_id = :'source'
  AND NOT EXISTS (SELECT 1 FROM meeting_utterance WHERE meeting_id = :'copy');
COMMIT;
SELECT count(*) FROM meeting_utterance WHERE meeting_id = :'copy';
"""

TRANSCRIPT = """
SELECT coalesce(string_agg(track || '/' || coalesce(speaker, '') || ':' || text, E'\\n'
                           ORDER BY start_ms, end_ms, track, speaker, text), '')
FROM meeting_utterance WHERE meeting_id = :'copy';
"""


def psql(sql: str, **variables: str) -> str:
    args = list(PSQL)
    for name, value in variables.items():
        args += ["-v", f"{name}={value}"]
    done = subprocess.run(args, input=sql, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        sys.exit(f"psql failed: {done.stderr.strip()[:500]}")
    return done.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--actor", type=uuid.UUID, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    sources = [uuid.UUID(value) for value in json.loads(args.sources.read_text(encoding="utf-8"))]
    copies = {}
    fingerprint = hashlib.sha256()
    for source in sources:
        copy = uuid.uuid5(NAMESPACE, str(source))
        lines = psql(COPY, source=str(source), copy=str(copy), actor=str(args.actor)).splitlines()
        count = int(lines[-1]) if lines else 0
        if count == 0:
            sys.exit(f"{source}: not an ended meeting with a transcript")
        transcript = psql(TRANSCRIPT, copy=str(copy))
        fingerprint.update(str(source).encode() + b"\0" + transcript.encode("utf-8") + b"\0")
        copies[str(source)] = {"copy": str(copy), "utterances": count}
        print(f"{source} -> {copy} ({count} lines)")
    result = {"fingerprint": fingerprint.hexdigest(), "copies": copies}
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("fingerprint", result["fingerprint"])


if __name__ == "__main__":
    main()
