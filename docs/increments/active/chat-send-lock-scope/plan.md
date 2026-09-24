# Plan

- [x] Split `ChatTurnService.command` into a locked admission (reservation and run registration) and an unlocked start (`mcp.open`, dispatch).
- [x] Settle every failure after registration through the run's terminal path so the permit, lease and MCP sessions are released once.
- [x] `ChatTurnServiceTest`: a slow `mcp.open` does not block send, Stop or subscribe of another session on the same stripe; a repeated request ID during `mcp.open` reserves once; Stop during `mcp.open` cancels without a model call and closes the tools; an `mcp.open` failure fails the reserved turn and returns the permit.
- [x] Update the Chat contract, the verification matrix and the owner decision.

## Verification — 2026-09-24

- `./gradlew :core:compileJava :core:compileTestJava --no-daemon`: passed.
- `./gradlew :core:test --no-daemon --tests 'io.memoryos.chat.*Turn*'`: passed — `ChatTurnServiceTest` 13/13, `ChatTurnSetupTest` 11/11.
- The same `ChatTurnServiceTest` against the previous `ChatTurnService` fails the four new cases: the three concurrency cases time out behind the held stripe, and the failure case shows the old direct `finish` without a stream outcome.
- Full `clean check` is left to CI.
