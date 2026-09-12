# Local verification — 2026-09-12

Scope: [design](design.md). Initial implementation was verified on base `7474d1879819b07e4211f136c2785e435fa38079`. Initial publication used `feat/mem-81-chat-ux-followup` from refreshed `origin/main` (`820f32e`, including PR #101), with authorization to open a PR and trigger CodeRabbit. The subsequent user directive authorizes fixing the captured finding, merging and deploying staging; the fix branch incorporates main `9f5b728` (PR #103). Existing OCR changes remain in the base; this PR does not modify OCR or CI/CD. No Linear status is inferred from local checks.

## Backend

- `./gradlew.bat clean check --no-daemon --console=plain`: PASS, 11m32s. JUnit reports contain 613 tests, 8 skipped (605 executed); opt-in live/provider/resource checks are not implicitly rerun.
- Automatic naming API test exercises the Spring API, CSRF/owner checks, real PostgreSQL, native runner and a synthetic provider. It verifies one inference for naming, no duplicate on repeated POST, original answer preservation and manual rename priority.
- PostgreSQL naming test verifies no claim before completion, owner denial, once-only claim, conditional update and a manual rename to the same string winning over late generation.
- File evidence tests distinguish indexed locations and reject mixed character/index locations. Private document-reader service tests check the requested generation before and after index IO. Text reader tests use Unicode character offsets, including an emoji.
- OpenAPI contract test regenerated and validated `openapi.yml`; SDK generation is reproducible.
- All modified Java files inspected using JetBrains with warnings enabled. New test nullability/generic warnings corrected. Retained pre-existing inspections concern positive boolean predicate usage, servlet streaming IOException analysis, and test-only loopback HTTP/custom CSRF/SSE headers required by those contracts. No unresolved references or compile errors. IDE inspection of generated `openapi.yml` timed out at both 10s and 60s, so it is not claimed IDE-clean; contract tests and SDK generation validate it instead.

## Browser and frontend

- Existing Chat, Project/editor/sharing and Search browser suites: 41 scenarios covered. Initial run passed 37; four selectors still expected old/ambiguous English/Vietnamese labels. After correcting exact labels, the four failed scenarios passed on rerun. No runtime checks were removed or weakened.
- Playwright CLI inspection at 1440×900 and 390×844 confirmed one composer action row, the on-demand paperclip popover, no inline management fieldset, and a truncated long header that leaves Share/overflow accessible.
- A browser naming fixture confirmed the header updates after the answer without replacing the conversation. The real naming API/native-provider boundary is covered separately above; the browser fixture is not live model evidence.
- Local screenshots: `output/playwright/chat-composer-desktop.png`, `chat-composer-mobile.png`, `chat-long-title-mobile.png`. These are local inspection artifacts, not production assets. The fixture server's missing `runtime-config.js` produced an expected 404; no production runtime was used.
- `corepack pnpm@11.22.0 --dir web check`: PASS on the final frontend changes, including generated-client stability, lint, formatting, TypeScript, **128 unit tests**, production build/font assets and route stability. The indexed-reader unit test supplies jsdom's missing scroll method; actual scroll/focus behavior remains covered by the browser suite. Existing Vite runtime-config external-script and >500kB chunk notices remain; no new dependency was added.

## Publication refresh

The latest main already contains `V39__support_one_hundred_mib_binary_inputs.sql`. The unshipped naming migration was renamed to `V40__chat_automatic_titles.sql`, leaving the existing V39 intact. The OpenAPI merge preserves main's Source upload contract and adds only the two Chat endpoints.

- Refreshed-base frontend `check`: PASS, 134 unit tests plus generated API, lint, formatting, TypeScript and production build.
- Refreshed-base `clean check`: NOT GREEN. The run reported `ChatFileLifecycleIntegrationTest.originalContentClosesOnMetadataMismatchOrConcurrentDeletion` failing with `NoSuchElementException` in the existing `dispatch()` helper (`getFirst()` on an empty claim list). That test/helper is unchanged by this PR. An immediate claim is time-gated in the real dispatch repository; a timing cause is suspected but not established by this failure alone. Do not substitute the initial base's green result for current-head verification.
- Secret scan of the scoped staged diff: PASS, no leaks. Local screenshots remain excluded from the PR.

## CodeRabbit fix verification

- The single captured review on `ca8a6c4` found that code-point truncation can split a joined family emoji or a combining mark. Reproduced at the 40-character boundary, then replaced with native `Intl.Segmenter` grapheme iteration. No dependency added. The fallback also stays inside the existing API UTF-16 length limit.
- Frontend `check` on refreshed main `9f5b728`: PASS, including 136 unit tests, generated API stability, lint, formatting, TypeScript and production build. Added regressions cover joined emoji, decomposed accented characters, exact-boundary titles and the API length ceiling.
- Playwright CLI on the real local frontend with the existing fixture API: first-send header and sidebar preserve all of the family emoji in `39 A + family emoji + ellipsis`; reload retains the title. This is browser regression evidence, not live provider or staging acceptance.
- Original PR-head CI [34672868675](https://github.com/kl3inIT/MemoryOS/actions/runs/34672868675) passed, including backend and both browser shards. That does not establish the root cause of the earlier local dispatch-helper failure. New-head local and CI verification remain separate.
- Refreshed-base `./gradlew.bat clean check --no-daemon --console=plain`: PASS in 9m16s. Core integration tests executed; unchanged API/connector/worker tasks reused Gradle cache. The earlier dispatch-helper failure did not recur; no claim is made that its suspected timing cause was fixed.

## Boundaries

The implementation reuses native assistant-ui attachment lifecycle and Radix controls. Indexed private citations share the Search reader; cached-text citations highlight their character window. Whole-file context/older citations still open as explicitly whole-file references, not fabricated PDF pages. Existing conversation names are not automatically rewritten. Naming is best-effort and retains the short fallback on failure. No live staging acceptance or live title-quality evaluation is claimed.
