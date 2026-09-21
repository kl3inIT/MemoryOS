# Implementation plan

One pull request on `kl3inIT/mem-139-usage-report`.

- [x] 1. PDF spike: embed Hanken Grotesk and render a Vietnamese string with PDFBox; a test proves the
      text survives extraction.
- [x] 2. Data: `V90__ai_usage_report.sql`; the export query over `ai_usage` (rows, members, Groups);
      `UsageReportData`, which aggregates as Onyx's `build_usage_report_data` does (top 10 people,
      top 8 models, flows and Groups, "Other (n)", daily cost and active people, idle members).
- [x] 3. Files: the CSV writer with its formula guard; the PDF renderer (cover, Adoption, Spend over time,
      By model, Heaviest users, By task, By Group, Internal and External, Members not using AI, note);
      the ZIP.
- [x] 4. Lifecycle: request, list and open for `MODELS_MANAGE`; claim with a lease, build, store through
      `ObjectWriteService`, mark ready or failed after three attempts; one recurring Worker task.
- [x] 5. API and contract: three endpoints; OpenAPI regenerated; `pnpm generate:api`.
- [x] 6. Web: a "Usage reports" section at the foot of AI costs, with Onyx's copy, a pending row polled
      while a report is pending, ready rows with a download link, an empty state and an error state.
- [x] 7. Tests: renderer, CSV guard, aggregation, HTTP lifecycle and authorization, vitest; screenshots.
- [x] 8. Docs: `docs/specs/ai-usage.md`, `docs/tests/ai-usage.md`, roadmap, architecture. `pnpm check` passed locally.
      The local `./gradlew clean check` was stopped for memory pressure after it caught a Spring Modulith
      violation (`usage` → `objectstorage`, now declared); the full Gradle gate runs in CI.
- [x] 9. Follow-up issue for VND conversion at an administrator's rate: MEM-151.
