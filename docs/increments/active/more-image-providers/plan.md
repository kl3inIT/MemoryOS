# Plan — More image providers

- [x] Check each candidate's current request and response shape; keep the four that answer one synchronous request with base64
- [x] Catalog sizes accept aspect ratios; `ImageCall` encodes and decodes data URIs
- [x] `GEMINI_IMAGE`, `OPENROUTER_IMAGE`, `XAI_IMAGE` and `FAL_AI` with their adapters; V165 widens the provider CHECK
- [x] Unit tests for each adapter's generation, edit and failure shapes; registry and catalog invariants
- [x] `openapi.yml` enums and the Hey API client
- [x] Web names, marks, sites and endpoint hints; logos under `public/provider-logos`
- [x] Chat spec and test matrix
- [x] `CUSTOM_HTTP`: `ImageTemplate` with validation, `CustomHttpImageAdapter` substituting into the body tree, the `template` column and its CHECK, API records `ImageTemplate`/`ImageTemplateStep`
- [x] Unit tests: template validation, prompt injection, missing values, data URI, raw, edit without a template, hosted URL, undisclosed failure
- [x] Web: the custom connection form with presets (subagent)
- [ ] Live connection test of each provider with a real key on staging
- [ ] `clean check` and `pnpm --dir web check` in CI
- [ ] Move to `completed/` and reconcile the roadmap once merged
