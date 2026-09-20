"""`rag-benchmark <command>`: freeze the corpus, run the questions, report against the baseline."""

from __future__ import annotations

import argparse
import sys
from datetime import UTC, datetime
from pathlib import Path

from . import corpus, report
from .client import ActorClient, BenchmarkError
from .config import Config, ConfigError
from .questions import QuestionError, load


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rag-benchmark", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    freeze = commands.add_parser(
        "freeze", help="write the corpus manifest the runs compare against"
    )
    freeze.add_argument("--actor", default=None, help="actor label to read the corpus as")

    check = commands.add_parser("check", help="validate the question file and report corpus drift")
    check.add_argument("--actor", default=None)

    run = commands.add_parser("run", help="run every question and write a report")
    run.add_argument("--label", default=datetime.now(UTC).strftime("%Y%m%d-%H%M"))
    run.add_argument(
        "--retrieval-only", action="store_true", help="skip Chat; measure the search page alone"
    )
    run.add_argument("--only", default=None, help="run one category")
    run.add_argument(
        "--save-baseline", action="store_true", help="store this run's scores as the baseline"
    )

    args = parser.parse_args(argv)
    try:
        config = Config.from_environment()
        return _dispatch(args, config)
    except (ConfigError, QuestionError, BenchmarkError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2


def _dispatch(args: argparse.Namespace, config: Config) -> int:
    manifest = config.data_dir / "corpus.json"
    questions_path = config.data_dir / "questions.jsonl"
    if args.command == "freeze":
        with _client(config, args.actor) as client:
            entries = corpus.freeze(client, manifest)
        print(f"{len(entries)} documents written to {manifest}")
        return 0
    if args.command == "check":
        questions = load(questions_path)
        print(f"{len(questions)} questions are valid")
        missing = _missing_gold(questions, manifest)
        for gap in missing:
            print(f"warning: {gap}")
        with _client(config, args.actor) as client:
            changes = corpus.drift(
                corpus.load(manifest), corpus.freeze(client, manifest.with_suffix(".current.json"))
            )
        for change in changes:
            print(f"drift: {change}")
        return 1 if changes or missing else 0
    return _run(args, config, questions_path)


def _run(args: argparse.Namespace, config: Config, questions_path: Path) -> int:
    from .run import execute  # imported here so `freeze` does not need the run machinery

    questions = load(questions_path)
    if args.only:
        questions = [question for question in questions if question.category == args.only]
    if not questions:
        print("error: no question matched", file=sys.stderr)
        return 2
    outcome = execute(config, questions, args.label, retrieval_only=args.retrieval_only)
    written = outcome.write(config.out_dir / f"{args.label}.json")
    scores = report.score(outcome, questions, config.recall_at)
    baseline_path = config.data_dir / "baseline.json"
    baseline = report.load_baseline(baseline_path)
    text = report.markdown(outcome, scores, baseline)
    (config.out_dir / f"{args.label}.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"results: {written}")
    for failed in report.failures(outcome.results):
        reason = failed.error or failed.judge_reason or f"leaked {','.join(failed.leaked)}"
        print(f"  {failed.id}: {reason}")
    if args.save_baseline:
        report.save_baseline(baseline_path, scores)
        print(f"baseline saved to {baseline_path}")
    return 1 if report.regressions(scores, baseline) else 0


def _client(config: Config, label: str | None) -> ActorClient:
    chosen = label or next(iter(config.actors))
    actor = config.actors.get(chosen)
    if actor is None:
        raise ConfigError(f"actor {chosen!r} is not configured")
    return ActorClient(config, actor)


def _missing_gold(questions: list, manifest: Path) -> list[str]:
    if not manifest.exists():
        return ["corpus.json is missing; run `rag-benchmark freeze` first"]
    known = {entry.document_id for entry in corpus.load(manifest)}
    return [
        f"{question.id}: gold document {document} is not in the frozen corpus"
        for question in questions
        for document in question.gold_document_ids
        if document not in known
    ]


if __name__ == "__main__":
    raise SystemExit(main())
