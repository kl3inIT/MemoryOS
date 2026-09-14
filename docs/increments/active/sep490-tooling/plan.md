# Import plan

- [x] Inspect and copy eight original templates; record checksums.
- [x] Add Vietnamese report mapping, missing requirements and workflow.
- [x] Import tracked Visual Paradigm MCP source with upstream commit provenance.
- [x] Ignore root output directory and retain local contents.
- [x] Run the tool's own Gradle wrapper: clean check.
- [ ] Publish scoped commit directly to main and record publication in Linear.

The tool remains an independent build and requires a locally installed Visual Paradigm. Compilation and unit tests do not establish interactive diagram creation or plugin acceptance.

Verification: tool Gradle 9.6.1 wrapper clean check passed with 18 tests, zero failures/errors/skips. All eight template hashes match source. IntelliJ did not resolve the nested build as its own imported project; IDE inspection is unavailable for this import. No interactive Visual Paradigm smoke test was performed.
