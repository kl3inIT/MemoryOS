"""`rag-benchmark <command>`: sign actors in, freeze the corpus, run the questions, report."""

from __future__ import annotations

import argparse
import sys
import webbrowser
from datetime import UTC, datetime
from pathlib import Path

import httpx

from . import auth, corpus, report
from .client import ActorClient, BenchmarkError
from .config import Config, ConfigError
from .questions import Question, QuestionError, for_mode, load


def main(argv: list[str] | None = None) -> int:
    # The report is Vietnamese; a Windows console defaults to a code page that cannot print it.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="rag-benchmark", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    login = commands.add_parser(
        "login", help="sign one actor in through the browser and keep its refresh token"
    )
    login.add_argument("--actor", required=True, help="actor label to sign in as")
    login.add_argument(
        "--no-browser",
        action="store_true",
        help="print the sign-in URL without opening a browser, for an automated browser",
    )

    freeze = commands.add_parser(
        "freeze", help="write each actor's corpus and their union, which runs compare against"
    )
    freeze.add_argument("--actor", default=None, help="freeze one actor only")

    check = commands.add_parser("check", help="validate the question file and report corpus drift")
    check.add_argument("--actor", default=None, help="check one actor only")

    run = commands.add_parser("run", help="run every question and write a report")
    run.add_argument("--label", default=datetime.now(UTC).strftime("%Y%m%d-%H%M"))
    run.add_argument(
        "--retrieval-only", action="store_true", help="skip Chat; measure the search page alone"
    )
    run.add_argument("--only", default=None, help="run one category")
    run.add_argument(
        "--save-baseline", action="store_true", help="store this run's scores as the baseline"
    )
    run.add_argument(
        "--grounded",
        action="store_true",
        help="the server answers in grounded mode: compare with baseline.grounded.json and fail "
        "on any uncited answer or answered sensitive question",
    )

    args = parser.parse_args(argv)
    try:
        config = Config.from_environment()
        return _dispatch(args, config)
    except (ConfigError, QuestionError, BenchmarkError, auth.AuthError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2


def _dispatch(args: argparse.Namespace, config: Config) -> int:
    union_path = config.data_dir / "corpus.json"
    questions_path = config.data_dir / "questions.jsonl"
    if args.command == "login":
        return _login(config, args.actor, open_browser=not args.no_browser)
    if args.command == "freeze":
        # Every question is written to find its gold documents, so the questions are probes too.
        probes = corpus.PROBES + tuple(
            question.question
            for question in (load(questions_path) if questions_path.exists() else [])
        )
        labels = _labels(config, args.actor)
        probed: dict[str, list[corpus.Entry]] = {}
        for label in labels:
            with _client(config, label) as client:
                probed[label] = corpus.freeze(
                    client, corpus.actor_path(config.data_dir, label), probes
                )
        # A document one actor found is checked for every other actor through the reader.
        candidates = corpus.union(
            [*probed.values(), corpus.load(union_path) if union_path.exists() else []]
        )
        manifests = []
        for label in labels:
            with _client(config, label) as client:
                entries = corpus.complete(
                    client, corpus.actor_path(config.data_dir, label), probed[label], candidates
                )
            print(
                f"{label}: {len(entries)} documents ({len(entries) - len(probed[label])} by reader)"
            )
            manifests.append(entries)
        if args.actor is None:
            corpus.write(union_path, corpus.union(manifests))
            print(f"union: {len(corpus.load(union_path))} documents written to {union_path}")
        return 0
    if args.command == "check":
        questions = load(questions_path)
        print(f"{len(questions)} questions are valid")
        problems = _consistency(questions, config) + _unknown_actors(questions, config)
        for problem in problems:
            print(f"warning: {problem}")
        drifted = False
        for label in _labels(config, args.actor):
            frozen_path = corpus.actor_path(config.data_dir, label)
            if not frozen_path.exists():
                print(f"warning: {label}: no frozen corpus; run `rag-benchmark freeze`")
                drifted = True
                continue
            with _client(config, label) as client:
                current = corpus.freeze(client, frozen_path.with_suffix(".current.json"))
            for change in corpus.drift(corpus.load(frozen_path), current):
                print(f"drift: {label}: {change}")
                drifted = True
        return 1 if drifted or problems else 0
    return _run(args, config, questions_path)


def _login(config: Config, label: str, open_browser: bool = True) -> int:
    if label not in config.actors:
        raise ConfigError(f"actor {label!r} is not in MEMORYOS_BENCHMARK_ACTORS")
    store = auth.TokenStore(config.token_store)
    with httpx.Client(timeout=config.timeout_seconds) as http:
        account = auth.login(
            config.issuer,
            config.login_client_id,
            config.login_port,
            label,
            store,
            http,
            open_browser=webbrowser.open if open_browser else _print_only,
        )
    print(
        f"{label}: signed in as {account or 'an account without email'}; token kept in {store.path}"
    )
    return 0


def _print_only(_url: str) -> None:
    """`login` already printed the URL; an automated browser opens it."""


def _run(args: argparse.Namespace, config: Config, questions_path: Path) -> int:
    from .run import execute  # imported here so `freeze` does not need the run machinery

    questions = for_mode(load(questions_path), args.grounded)
    if args.only:
        questions = [question for question in questions if question.category == args.only]
    if not questions:
        print("error: no question matched", file=sys.stderr)
        return 2
    unknown = _unknown_actors(questions, config)
    if unknown:
        raise ConfigError("; ".join(unknown))
    readable = {
        label: {entry.document_id for entry in corpus.load(path)}
        for label in config.actors
        if (path := corpus.actor_path(config.data_dir, label)).exists()
    }
    outcome = execute(
        config,
        questions,
        args.label,
        retrieval_only=args.retrieval_only,
        readable=readable,
        grounded=args.grounded,
    )
    written = outcome.write(config.out_dir / f"{args.label}.json")
    scores = report.score(outcome, questions, config.recall_at)
    # Grounded mode trades answers for refusals, so it is compared only with a grounded run.
    baseline_path = config.data_dir / (
        "baseline.grounded.json" if args.grounded else "baseline.json"
    )
    baseline = report.load_baseline(baseline_path)
    text = report.markdown(outcome, scores, baseline)
    (config.out_dir / f"{args.label}.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"results: {written}")
    for failed in report.failures(outcome.results, grounded=args.grounded):
        reason = (
            failed.error
            or (failed.leaked and f"leaked documents {','.join(failed.leaked)}")
            or (failed.leaked_facts and f"leaked facts {'; '.join(failed.leaked_facts)}")
            or (failed.outside_corpus and f"outside corpus {','.join(failed.outside_corpus)}")
            or (args.grounded and failed.asserted_uncited and "asserted without citation")
            or failed.judge_reason
        )
        print(f"  {failed.id} [{failed.actor}]: {reason}")
    if args.save_baseline:
        report.save_baseline(baseline_path, scores)
        print(f"baseline saved to {baseline_path}")
    return 1 if report.regressions(scores, baseline, grounded=args.grounded) else 0


def _labels(config: Config, label: str | None) -> list[str]:
    if label is None:
        return list(config.actors)
    if label not in config.actors:
        raise ConfigError(f"actor {label!r} is not configured")
    return [label]


def _client(config: Config, label: str) -> ActorClient:
    return ActorClient(config, config.actors[label])


def _unknown_actors(questions: list[Question], config: Config) -> list[str]:
    return [
        f"{question.id}: actor {expectation.actor!r} is not in MEMORYOS_BENCHMARK_ACTORS"
        for question in questions
        for expectation in question.expectations()
        if expectation.actor not in config.actors
    ]


def _consistency(questions: list[Question], config: Config) -> list[str]:
    """
    Each actor's gold documents must be in its corpus and its forbidden documents must not be:
    a question whose expectation disagrees with the Group configuration measures nothing.
    """
    union_path = config.data_dir / "corpus.json"
    if not union_path.exists():
        return ["corpus.json is missing; run `rag-benchmark freeze` first"]
    known = {entry.document_id for entry in corpus.load(union_path)}
    readable = {
        label: {entry.document_id for entry in corpus.load(path)}
        for label in config.actors
        if (path := corpus.actor_path(config.data_dir, label)).exists()
    }
    problems: list[str] = []
    for question in questions:
        for expectation in question.expectations():
            where = f"{question.id} [{expectation.actor}]"
            mine = readable.get(expectation.actor, known)
            problems += [
                f"{where}: gold document {document} is not in this actor's corpus"
                for document in expectation.gold_document_ids
                if document not in mine
            ]
            if expectation.actor in readable:
                problems += [
                    f"{where}: this actor can read forbidden document {document}"
                    for document in expectation.forbidden_document_ids
                    if document in mine
                ]
    return problems


if __name__ == "__main__":
    raise SystemExit(main())
