# Verification

## Names (pull request #605, `f1836a36`), 2026-10-12

CI gate green with both image jobs. `Deploy staging` succeeded: API, Worker and web healthy on that revision, and
the Flyway history unchanged at 154 applied migrations, so the release bundle's migration path and `deploy.sh`'s
check of it agree after the rename. The first CI round failed in `infrastructure`: the interpreter evaluation under
`tools/` read `core/…/ChatPrompts.java`, a path the rewrite had skipped with the rest of `tools/`; the same search
found `scripts/sync-chat-known-models.mjs`, which builds its path from parts and which no job runs.

## Platforms (pull request #607, `0a7e440b`), 2026-10-12

Locally, before the push: the seven modules compiled with their tests; Lark 31, Google 56, Microsoft 33 and
extraction 185 tests passed; core's `ModulithArchitectureTest`, `CoreDependencyRulesTest` and `RetryAfterTest`
passed; `checkRuntimeClasspathIsolation` passed and failed as it should with a forbidden group added. CI gate green
on the first round.

On staging, release `0a7e440b`:

| Check | Result |
| --- | --- |
| Start | API and Worker healthy; the router requires a reader for every provider format at start, and none was missing |
| Flyway | 154 applied migrations, unchanged |
| Lark Source of MEM-245 | A requested run completed; the reader mapped to Lan found the Drive documents and the report her department is granted, the one mapped to Minh the ten Wiki documents, and the two accounts without a Lark person nothing |
| Credential lists | Lark, SharePoint and Google Drive answered 200 |
| Extraction through the new router | A Lark sheet and a Lark document (their registered snapshot readers) and a PDF (binary, Docling) were indexed again and succeeded |

Not exercised: a Google Docs or Sheets snapshot, a SharePoint page and an XLSX upload, of which staging holds no
indexed item; their readers are covered by the module tests and by the start-up requirement.
