# Implementation plan

- [x] Reproduce the live failure and distinguish Search readiness from Chat tool selection.
- [x] Inspect the Onyx `06aa2b0` automatic and forced-tool paths.
- [x] Check Spring AI tool-choice portability through the official Context7 documentation source.
- [x] Harden internal-document grounding guidance only when `search_knowledge` is callable.
- [x] Add the focused prompt regression test and update the Chat verification matrix.
- [x] Run focused prompt tests and the complete `core:check` gate.
- [x] Run the repository-wide `clean check` gate.
- [ ] Repeat authenticated browser acceptance after the running API uses the new build; Playwright reached real Keycloak but its isolated profile has no credentials. Require Search activity and citations.
