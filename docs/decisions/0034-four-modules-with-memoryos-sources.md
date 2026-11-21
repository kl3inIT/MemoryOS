# 34. Four Gradle modules again: the platform adapters and extraction are `memoryos-sources`

Date: 2026-11-22

## Status

Accepted by the owner on 2026-11-22 and implemented in step 3 of the
[capstone cleanup](../increments/active/capstone-cleanup/plan.md). Supersedes
[ADR 0027](0027-integrations-by-platform-and-memoryos-module-names.md) except its module names and its check that no
platform SDK reaches `memoryos-core`; [ADR 0016](0016-integration-bundle-named-sources.md) is current again for the
bundle and its packages.

## Context

ADR 0016 (2026-09-25) kept every platform adapter and content extraction in one `sources` module, packaged by the
capability each class serves. On 2026-10-12, the day after the SEP490 requirements froze, ADR 0027 split it by
platform because "a Lark bot is coming beside the Lark connector": `memoryos-integration-lark`, `-google`,
`-microsoft`, then `-opensandbox`, `-zalo` and `-whatsapp`, beside `memoryos-extraction`. ADR 0032 removed the chat
bots and ADR 0033 the sandbox, so each remaining platform module serves one capability, `connector`, and the reason
for the split is gone. The owner names the split, not only the bots, as the drift from the capstone's design; the
Report 4 package diagram shows the layout of 2026-10-11.

## Decision

- **Four Gradle modules:** `memoryos-core`, `memoryos-sources`, `memoryos-api`, `memoryos-worker`, as at
  `d756db3d` (2026-10-11). The `memoryos-` names of ADR 0027 stay; the directories, Dockerfiles, workflows and the
  release bundle's migration path keep them.
- **`memoryos-sources` is ADR 0016's bundle.** Its packages follow the capability each class serves:
  `io.memoryos.connector.adapter.googledrive`, `.sharepoint` and `.lark` (each platform's client, gateway, snapshot
  readers and auto-configuration) and `io.memoryos.ingestion.extraction` (Tika, Docling, the OCR adapters and
  readers, the spreadsheet readers, the extractor router). No package is split with `core`. `api` depends on it for
  compilation and `worker` at runtime.
- **One dependency rules test,** `SourcesDependencyRulesTest`: the bundle uses only public capability APIs (no
  `application` or `persistence` package), a connector adapter does not depend on `ingestion`, and extraction names
  no platform adapter. The per-platform isolation between integrations goes.
- **Kept from ADR 0027:** the `memoryos.classpath-isolation` check fails `memoryos-core`'s build when a platform SDK
  or a parser reaches its runtime classpath; a snapshot reader is registered by its adapter for its input format, so
  the router names no platform.
- The move changes no behavior: classes, configuration property prefixes and bean names are unchanged; only package
  names and the auto-configuration imports file change.

## Consequences

- One build file states every platform SDK and parser; an SDK upgrade or a new platform touches `memoryos-sources`
  only, and `memoryos-core` stays free of them.
- A platform that later serves a second capability adds a package beside its first in the bundle, under the
  capability it serves; whoever needs a module per platform again starts from a new decision.
- Documents of completed increments name the paths of their time and are not rewritten.
