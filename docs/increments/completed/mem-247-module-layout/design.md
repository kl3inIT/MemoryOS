# Integrations by platform, and modules named `memoryos-*` (MEM-247)

## Why

[ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md) keeps every integration in one Gradle
module, `sources`, whose packages follow the capability each adapter serves (`connector.adapter.<provider>`,
`ingestion.extraction`). Two things no longer fit.

- **The unit that changes together is the platform.** A Lark bot ([MEM-246](https://linear.app/memory-os/issue/MEM-246))
  reads its token, sends its requests and counts them exactly as the Lark connector does; an SDK upgrade or a
  change of scope touches both. The Lark connector and the Google Drive connector share only `RetryAfter`. Laid
  out by capability, the shared Lark code has no home that is not a third package beside two others.
- **`sources` names one kind of thing.** The module already holds content extraction, and a chat channel is not a
  source.

The owner also wants one name for a module everywhere: its directory, its Gradle project and its jar.

## Target

```
memoryos-core/                    capabilities; connector and channel stay separate modules of Spring Modulith
memoryos-api/
memoryos-worker/
memoryos-integration-lark/        io.memoryos.integration.lark
memoryos-integration-google/      io.memoryos.integration.google
memoryos-integration-microsoft/   io.memoryos.integration.microsoft
memoryos-extraction/              io.memoryos.ingestion.extraction (unchanged)
```

A platform module holds everything that calls that platform, in packages by what it serves, which may not import
each other; a rule test holds that. The capabilities keep their ports in `memoryos-core`, so an integration still
depends on public `core` APIs only. Extraction keeps its package: its auto-configuration classes are named in
`MemoryOsApiApplication` and in tests, and renaming them buys nothing.

Rejected: one module a capability and platform (`connector-lark`, `channel-lark`, a shared `platform-lark`), three
modules for about three shared files when both deployables need both; and a `channels` module beside `sources`,
which must depend on `sources` for the Lark client or copy it.

## What is not a move

| Change | Reason |
| --- | --- |
| `SourceContentExtractorRouter` chooses a snapshot reader from those registered for an input format | It imports the Google Docs, Google Sheets, SharePoint page and Lark readers by name; extraction must not depend on a platform module. Each platform contributes its readers as beans of a port in `core` |
| `RetryAfter` moves to `core`'s shared kernel | The only code the three adapters share |
| Convention plugin (`build-logic`) | The Java toolchain, JUnit settings and test system properties leave the root `subprojects` block, so a module's build file states only what is its own |
| A resolution-time check on runtime classpaths | Nothing fails to compile when a platform SDK reaches `core` or another platform through a transitive dependency |

Configuration prefixes (`memoryos.lark.*`, `memoryos.google-drive.*`, …) and `@Bean` names do not change, so no
environment file on a server changes.

## Two pull requests

1. **Names.** `core`, `api`, `worker` become `memoryos-core`, `memoryos-api`, `memoryos-worker`; the convention
   plugin. No Java source changes. It proves the build, the image and the deployment survive a rename before any
   code moves.
2. **Platforms.** `sources` becomes the four modules above, with the changes of the table.

The accepted ADR that supersedes 0016 is written with the first pull request, when implementation starts.

## Where a path is load-bearing

- `infrastructure/deployment/deploy.sh` refuses a candidate that lacks an applied migration by looking for
  `source/core/src/main/resources/db/migration/V<n>__*.sql` in the release bundle, which CI builds with
  `git archive … core/src/main/resources/db/migration`. The script comes from the same bundle, so both change in
  one commit; a mismatch stops every deployment before it touches a container.
- `Dockerfile` copies each module's build file and source, finds the boot jars under `api/build/libs` and
  `worker/build/libs`, and copies the entry point and launcher from `api/src/main/docker`.
- CI: the changed-area filter, the test matrix legs (`:core:check`, `:api:check`, `:sources:check :worker:check`),
  the result and diagnostics paths, `shellcheck` on the launcher.
- Eight deployment tests read files by module path (`test_compose_layering.py`,
  `test_configuration_reaches_the_container.py`, `test_database_pool_sizing.py`, `test_deploy_workflow.py`,
  `test_launcher_secret_files.py`, `test_server_secret_sources.py`, `test_realm_optional_surfaces.py`).
- `scripts/sync-chat-known-models.mjs`, the `run-memoryos` and `memoryos-ide-static-analysis` skills,
  `.claude/rules/backend.md`, `.gitleaksignore`, the CodeRabbit path filters.

Documents of completed increments describe the repository as it was and are not rewritten; a link from a current
document into the source tree is corrected.

## Not in this increment

Native images, images built by Gradle, retried tests, the Lark bot itself, and an ahead-of-time cache for the JVM
(a candidate once measured on staging).
