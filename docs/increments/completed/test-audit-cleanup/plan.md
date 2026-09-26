# Test-audit cleanup — plan

- [x] Read-only audit in four lanes; candidates recorded in [design.md](design.md).
- [x] Web: remove dead `WebSearch`, the duplicate theme test and the copied embedding success case; test the thread adapter, socket URLs and correction folding through their public entry points and unexport the helpers.
- [x] Backend: add the boundary assertions first (undated OpenSearch window, `COMPLETED_WITH_ERRORS` operation status, object close in the artifact lifecycle), then remove the weaker tests, the unused constructor and the spike harness.
- [x] Tooling: drop the tautological interpreter version test; replace copied dataset counts with the category floor.
- [x] Matrices: `docs/tests/chat.md`, `connector.md`, `document.md` and the undated-documents plan point at the remaining owners.
- [x] CI green on the pull request, including the Docker-backed OpenSearch, PostgreSQL and API suites (PR #376 merged).

## Verification

Recorded in the pull request; heavy Docker suites run in CI because the development machine is short of memory.
