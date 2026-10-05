"""`python -m memoryos_bench login|provision|fingerprint`."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from memoryos_bench.api import Api
from memoryos_bench.auth import RoleSession, TokenStore, login
from memoryos_bench.provision import fingerprint, provision

CORPUS = Path(__file__).resolve().parent.parent / "corpus"
HOME = Path(os.environ.get("MEMORYOS_BENCH_HOME", Path.home() / ".memoryos-bench"))


def _json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    # Group and Source names are Vietnamese; a Windows console would otherwise fail on the first one.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="memoryos_bench")
    parser.add_argument(
        "--home",
        type=Path,
        default=HOME,
        help="token store and download cache (default: $MEMORYOS_BENCH_HOME)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    sign_in = commands.add_parser(
        "login", help="sign one role in through the browser (offline token)"
    )
    sign_in.add_argument(
        "--role", required=True, nargs="+", help="one or more roles, signed in in turn"
    )
    sign_in.add_argument(
        "--issuer",
        default=os.environ.get("MEMORYOS_ISSUER", "https://auth.kl3in.tech/realms/memoryos"),
    )

    build = commands.add_parser("provision", help="build the benchmark Sources, Groups and members")
    build.add_argument(
        "--origin",
        default=os.environ.get("MEMORYOS_ORIGIN", "https://memoryos.72-62-193-33.nip.io"),
    )
    build.add_argument(
        "--wait", type=float, default=3600, help="seconds to wait for indexing; 0 skips the wait"
    )
    build.add_argument(
        "--prune",
        action="store_true",
        help="remove files and memberships the layout does not list instead of failing",
    )

    commands.add_parser("fingerprint", help="print the corpus fingerprint")

    args = parser.parse_args(argv)
    manifest, layout = _json(CORPUS / "manifest.json"), _json(CORPUS / "layout.json")
    store = TokenStore(args.home / "tokens.json")

    if args.command == "login":
        for role in args.role:
            login(store, role, args.issuer)
        return 0
    if args.command == "fingerprint":
        print(fingerprint(manifest, layout))
        return 0

    # Each role's actor is whoever signed in for it, so no account is named anywhere.
    actors = {
        role: Api(args.origin, RoleSession(store, role)).get("/api/identity/me")["actorId"]
        for role in layout["roles"]
    }
    api = Api(args.origin, RoleSession(store, layout["administrator"]))
    report = provision(api, manifest, layout, actors, args.home / "corpus", args.wait, args.prune)
    print(f"fingerprint {fingerprint(manifest, layout)}")
    if report.would_remove:
        print()
        print("Memberships --prune would remove (each lets a role read beyond its questions):")
        for line in report.would_remove:
            print("  " + line)
    if report.problems:
        print(f"{len(report.problems)} problem(s); nothing above was removed", file=sys.stderr)
        return 1
    print("corpus ready" if report.created else "corpus unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
