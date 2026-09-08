# MEM-75 selected dependency upgrades

## Approved scope

Apply the user's selected Dependabot proposals together on main `172b2ac5ed57c32ce791fdcf7df19179d02d86df`: springdoc 3.1.0 (#20), setup-java v6 (#55), Nginx 1.31 Alpine (#24), React Table 9.2.3 (#48), React Router 1.170.32 (#49), React Query 5.102.8 (#50), React DOM types 19.2.5 (#51), and Lucide React 1.34.0 (#52). Preserve the proposed action SHA and image digest and regenerate the shared pnpm lockfile using the pinned package manager.

The user chose Java 25, so close JRE 26 proposal #53 and retain the current runtime image. Node 26 proposal #9 is diagnosed but not included: its image no longer bundles Corepack while the Dockerfile invokes it; Node 24 remains the supported build runtime. Other Dependabot proposals remain outside this selected batch. MEM-75 remains open while the wider backlog is unfinished.

## Implementation and verification

One replacement PR verifies the selected upgrades together. It owns any required compatibility fix or generated-contract change exposed by the actual tests. Do not hand-edit lockfile peer snapshots or generated API/route outputs, change runtime modes, weaken assertions, or add tests that merely restate version pins.

Springdoc can affect the generated OpenAPI contract. Run `OpenApiContractTest` and inspect any difference before regenerating `openapi.yml` and its client. Retain the disabled default documentation endpoint and existing security boundaries.

React libraries affect data state, tables and navigation. Run the full frontend check and zero-retry browser suite, including generated route/client stability. Nginx verification builds the actual web image and exercises its entrypoint/configuration, SPA routes, asset caching, security headers and proxied paths with an isolated synthetic upstream. This proves the Nginx boundary, not staging authentication or feature acceptance.

Require per-file IDE inspection, Linux workflow lint, the terminating `clean check`, current-head CI and one CodeRabbit pass. After authorized merge, verify main CI and publication for the exact merge SHA, then close the eight superseded Dependabot PRs with a link to the replacement. Record post-merge evidence in Linear. Preserve unrelated roadmap, Google Drive and chunking files.

## Upstream changes and current relevance

| Upgrade | Upstream improvement | MemoryOS relevance and migration boundary |
| --- | --- | --- |
| springdoc 3.0.3 → 3.1.0 | Boot 4.1 baseline; fixes disappearing descriptions, record naming and unstable Page schema order | Existing OpenAPI contract and generated client remain unchanged after verification; normal runtime documentation endpoints remain disabled |
| setup-java v5 → v6.0.0 | Vendor checksum verification, stricter input validation, Temurin cache fast-path improvements and additional cache controls | Current Temurin 25 inputs remain valid; Gradle caching remains owned by setup-gradle, so setup-java cache features are not newly enabled |
| Nginx 1.30 Alpine → pinned 1.31 Alpine | The selected digest runs 1.31.4: upstream notes include backend Host/authority handling, worker/module fixes and stream/mail PROXY v2 support | Real image smoke covers this application's HTTP proxy, SPA, headers and invitation logging; no stream/mail or HTTP/3 feature is enabled. This pin does not include 1.31.5 |
| React Query 5.101.4 → 5.102.8 | Releases completed fetch/mutation retryers to reduce retained data, avoids stale timers for disabled observers, reduces observer churn and bundle size; fixes mutation resubscription stuck in pending | Search and management pages use conditional queries and mutations. These are relevant upstream fixes, not a measured app speedup. New query/infiniteQuery methods deprecate older imperative methods; current fetchQuery calls are confined to tests and remain supported. Removed experimental render-prefetch/result promise APIs are not used |
| React Router 1.170.31 → 1.170.32 | Preserves context during reloads and builds client preload locations on demand | Relevant to the application's session/router context; generated routes and browser navigation remain compatible. Existing router-plugin stays pinned and retains its own router-core version |
| React Table 9.1.2 → 9.2.3 | Fixes parent-first flattened row ordering, filter metadata preservation and nested worker row-model round-trips | Current flat management tables do not exercise the new hierarchical/worker fixes; existing table flows remain verified |
| React DOM types 19.2.4 → 19.2.5 | Adds an optional diagnostic reason to the canary browser() declaration | Published package comparison shows no stable API declaration change. The app does not import the canary API; React DOM runtime stays 19.2.8 |
| Lucide React 1.33.0 → 1.34.0 | Adds the mail-clock icon | Available for future UI work; existing screens and icons are unchanged |

## Sources

- [springdoc 3.1.0 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.0): Boot 4.1 baseline and schema fixes.
- [setup-java v6 release](https://github.com/actions/setup-java/releases/tag/v6.0.0): existing Temurin/Java 25 inputs retained.
- [Corepack distribution policy](https://github.com/nodejs/corepack#default-installs): no longer bundled from Node 25.
- [Nginx changelog](https://nginx.org/en/CHANGES): use the actual image's 1.31.4 section, not the moving tag's newest release.
- [Query Core changelog at the selected release](https://github.com/TanStack/query/blob/%40tanstack/react-query%405.102.8/packages/query-core/CHANGELOG.md) and [React Query changelog](https://github.com/TanStack/query/blob/%40tanstack/react-query%405.102.8/packages/react-query/CHANGELOG.md).
- [Router Core changelog at the selected release](https://github.com/TanStack/router/blob/%40tanstack/react-router%401.170.32/packages/router-core/CHANGELOG.md).
- [Table Core changelog at the selected release](https://github.com/TanStack/table/blob/%40tanstack/react-table%409.2.3/packages/table-core/CHANGELOG.md).
- Published React DOM type packages: [19.2.4](https://registry.npmjs.org/@types/react-dom/19.2.4) and [19.2.5](https://registry.npmjs.org/@types/react-dom/19.2.5); only canary.d.ts changes besides release metadata.
- [Lucide 1.34.0 release](https://github.com/lucide-icons/lucide/releases/tag/1.34.0).

Staging auto-deployment configuration and live rollback acceptance remain MEM-70 work. No production rollout or additional dependency family is included.
