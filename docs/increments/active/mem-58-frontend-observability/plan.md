# MEM-58 implementation plan

- [x] Compare Sentry and Faro/Alloy against the MEM-57 LGTM stack and staging capacity.
- [x] Record the selected local Sentry Cloud error-only baseline and privacy boundary.
- [x] Initialise the browser SDK and capture the existing root React ErrorBoundary once.
- [x] Keep the React ErrorBoundary at the application boundary; do not add per-component boundaries.
- [x] Add selected, privacy-safe capture for FILE upload, Google Drive sync, indexing, and Search system failures.
- [x] Verify local event receipt with an intentional smoke event and retain no test code. On 2026-09-11, an intentionally blocked local Search API request produced the expected Sentry event; the event contained the safe workflow tags and no product payload was added.
- [x] Implement a non-cacheable Nginx runtime-config path without weakening immutable-image deployment.
- [ ] Add private source-map/release upload through a CI-only token; verify a minified staging error.
- [ ] Prove or reject browser-to-API W3C trace correlation against Tempo.
- [ ] Reconcile the final provider decision and acceptance evidence with Linear MEM-58.
