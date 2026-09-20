# Implementation plan

- [x] Reproduce the live failure and distinguish Search readiness from Chat tool selection.
- [x] Inspect the Onyx `06aa2b0` automatic and forced-tool paths.
- [x] Check Spring AI tool-choice portability through the official Context7 documentation source.
- [x] Harden internal-document grounding guidance only when `search_knowledge` is callable.
- [x] Add the focused prompt regression test and update the Chat verification matrix.
- [x] Run focused prompt tests and the complete `core:check` gate.
- [x] Run the repository-wide `clean check` gate.
- [x] Repeat authenticated browser acceptance after the running API uses the new build; require Search activity and citations. (2026-09-20: owner acceptance on staging.)
