# Implementation plan

Design: [design.md](design.md). Each pull request changes no behavior and is accepted when CI is green, staging
deployed it and the checks below pass. A pull request whose CI is still red after two rounds of fixes stops for
the owner. Production is not deployed while either is open.

## 1. Names

- [ ] ADR superseding [0016](../../../decisions/0016-integration-bundle-named-sources.md): integrations by
  platform, modules named `memoryos-*`, convention plugin, classpath isolation.
- [ ] `build-logic` convention plugin: Java 25 toolchain, JUnit platform, the ten-minute test timeout, the report
  settings and the object-storage test properties now in the root `subprojects` block.
- [ ] Rename the directories and Gradle projects `core`, `api`, `worker`; root `clean` and `check`.
- [ ] `Dockerfile`; `ci.yml` (changed-area filter, matrix legs, result and diagnostics paths, `shellcheck`, the
  migration path of the release bundle) with `deploy.sh`'s migration check in the same commit.
- [ ] The deployment tests that read files by module path; `scripts/sync-chat-known-models.mjs`.
- [ ] Current documents (`README.md`, `ARCHITECTURE.md`, `AGENTS.md`, guidelines, specs, test matrices,
  runbooks), the skills, `.claude/rules/backend.md`, `.gitleaksignore`, CodeRabbit filters.
- [ ] Acceptance: CI gate green with both image jobs; `Deploy staging` succeeds; API and Worker healthy; Flyway
  history unchanged.

## 2. Platforms

- [ ] Port in `core` for a snapshot reader keyed by input format; `SourceContentExtractorRouter` chooses from the
  registered readers; a test for each format and for a format nobody registered.
- [ ] `RetryAfter` and its test to `core`'s shared kernel.
- [ ] `memoryos-integration-lark`, `-google`, `-microsoft`, `memoryos-extraction`: sources, tests, fixtures,
  `AutoConfiguration.imports`, each declaring only its own SDK, with a `description` and a reason beside each
  dependency.
- [ ] `memoryos-api` and `memoryos-worker` depend on the four; the API's excluded auto-configurations keep their
  names.
- [ ] Classpath isolation check on `check`: no platform SDK on `memoryos-core`'s runtime classpath, none of
  another platform's on an integration's.
- [ ] `SourcesDependencyRulesTest` becomes a rule test a module; in a platform module, packages serving different
  capabilities do not import each other.
- [ ] `Dockerfile`, CI filter and legs, deployment tests naming `sources/`.
- [ ] Acceptance on staging: the Lark Source of MEM-245 synchronizes and the two readers still find what Lark
  shares with them; the Google Drive and SharePoint credential lists load; an uploaded PDF and an uploaded
  spreadsheet are extracted and searchable.

## 3. Consolidation

- [ ] `ARCHITECTURE.md` (deployables and module map), `docs/guidelines/backend.md` ("Where things live"),
  `docs/conventions.md` where it names the bundle, `AGENTS.md`.
- [ ] Roadmap; the increment moves to `completed/` with its verification.
