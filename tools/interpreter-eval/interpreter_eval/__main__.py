"""Compare the run_python guidance and executor guards of two refs on the same tasks.

python -m interpreter_eval --baseline origin/main --candidate WORKTREE --repeats 5
"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from interpreter_eval import runner, sources, tasks


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="interpreter-eval", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--baseline", default="origin/main", help="git ref, or WORKTREE for the working tree")
    parser.add_argument("--candidate", default=sources.WORKTREE, help="git ref, or WORKTREE for the working tree")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--sandbox", choices=["docker", "local"], default="docker")
    parser.add_argument("--image", help="executor image for --sandbox docker")
    parser.add_argument("--task", action="append", choices=sorted(tasks.TASKS), help="run only these tasks")
    parser.add_argument("--out", type=Path, default=Path("results.json"))
    args = parser.parse_args()
    if args.sandbox == "docker" and not args.image:
        parser.error("--sandbox docker needs --image (the memoryos-interpreter-executor image to run scripts in)")

    model = runner.Model(
        base_url=os.environ.get("INTERPRETER_EVAL_BASE_URL") or parser.error("INTERPRETER_EVAL_BASE_URL is not set"),
        api_key=os.environ.get("INTERPRETER_EVAL_API_KEY") or parser.error("INTERPRETER_EVAL_API_KEY is not set"),
        name=os.environ.get("INTERPRETER_EVAL_MODEL", "cx/gpt-6-luna"),
    )
    sandbox = runner.Sandbox(args.sandbox, args.image)
    if args.sandbox == "local":
        runner.executor_python()
    arms = {"baseline": sources.arm(args.baseline), "candidate": sources.arm(args.candidate)}
    selected = args.task or list(tasks.TASKS)

    with tempfile.TemporaryDirectory(prefix="interpreter-eval-fixtures-") as directory:
        fixtures = Path(directory)
        tasks.build_fixtures(fixtures)
        expected = tasks.reference(fixtures)
        jobs = [
            (arm, guidance, site, task)
            for _ in range(args.repeats)
            for arm, (guidance, site) in arms.items()
            for task in selected
        ]
        results: list[dict[str, object]] = []
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [
                pool.submit(
                    runner.trial, model, sandbox, fixtures, arm, guidance, site, task, tasks.TASKS[task], expected[task]
                )
                for arm, guidance, site, task in jobs
            ]
            for future in futures:
                try:
                    results.append(future.result())
                except Exception as error:  # noqa: BLE001 - one failed call must not lose the others
                    results.append({"error": repr(error)[:300]})
                print(".", end="", flush=True)
    print()
    args.out.write_text(
        json.dumps(
            {
                "baseline": args.baseline,
                "candidate": args.candidate,
                "model": model.name,
                "sandbox": args.sandbox,
                "results": results,
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf8",
    )
    print(summary(results, selected, args.repeats))
    print(f"\nFull results: {args.out}")


def summary(results: list[dict[str, object]], selected: list[str], repeats: int) -> str:
    rows = ["| task | baseline | candidate |", "| --- | --- | --- |"]
    for task in selected:
        cells = []
        for arm in ("baseline", "candidate"):
            runs = [r for r in results if r.get("arm") == arm and r.get("task") == task]
            cells.append(f"{sum(r['first']['correct'] for r in runs)}/{sum(r['final']['correct'] for r in runs)}")
        rows.append(f"| {task} | {cells[0]} | {cells[1]} |")
    totals = []
    for arm in ("baseline", "candidate"):
        runs = [r for r in results if r.get("arm") == arm]
        silent = sum(r["final"]["exit"] == 0 and not r["final"]["correct"] for r in runs)
        totals.append(
            f"{arm}: first {sum(r['first']['correct'] for r in runs)}, after one fix "
            f"{sum(r['final']['correct'] for r in runs)} of {len(runs)}; wrong with no error {silent}"
        )
    failed = sum("error" in r and "arm" not in r for r in results)
    return "\n".join(
        [
            *rows,
            "",
            f"cells: correct on the first run / after at most one fix, of {repeats}",
            *totals,
            f"failed model calls: {failed}",
        ]
    )


if __name__ == "__main__":
    main()
