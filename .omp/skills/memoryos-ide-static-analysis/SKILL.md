---
name: memoryos-ide-static-analysis
description: "MemoryOS Gate-1 static checks. When JetBrains MCP is connected, inspect every created or edited Java, Kotlin DSL, YAML, properties, and XML file with get_file_problems, including warnings; then compile. Trigger for MemoryOS code changes, static analysis, IDE inspection, JetBrains MCP, IntelliJ checks, Gradle configuration changes, or before claiming a MemoryOS change is verified."
---

# MemoryOS IDE static analysis

Gate 1 for this repository: every created or edited IDE-supported file receives a JetBrains semantic inspection, then the affected Gradle modules compile. Static analysis complements, but never replaces, behavioral verification.

## Primary path: JetBrains MCP

For Java, Spring, Kotlin DSL, Gradle, YAML, properties, and XML diagnostics, JetBrains MCP is the primary tool whenever its server is available. Run JetBrains inspection before consulting LSP diagnostics; do not downgrade to LSP merely because native JetBrains tool routes are absent when the configured JetBrains MCP endpoint is healthy.

1. Resolve the absolute repository root from the current working directory and pass it as `projectPath` on every JetBrains call. Never hard-code another developer's checkout path.
2. Read the mounted tool schema before its first use, for example `xd://mcp__jetbrains_get_file_problems`. The mounted plugin schema is authoritative.
3. Call `get_file_problems` once for every created or edited IDE-supported file.
4. Set `errorsOnly` to `false`. Warnings can identify unresolved Gradle references, invalid Spring configuration, or language-level compatibility risks.
5. Fix every error and every unresolved-reference or invalid-configuration warning. Repeat inspection until clean.
6. Retain a warning only when runtime compatibility requires the conventional form; record the reason. Example: keep a conventional `public static void main` when a framework launcher depends on it even if Java 25 reports `public` as redundant.

Example:

```json
{
  "filePath": "core/src/main/java/io/memoryos/authorization/AuthorizationService.java",
  "errorsOnly": false,
  "timeout": 10000,
  "projectPath": "D:/path/to/MemoryOS"
}
```

Parallelize independent per-file inspections. Never inspect only a representative sample.

## Tool choice

- `get_file_problems`: required per-file semantic inspection.
- `get_project_modules`: confirm IntelliJ imported `core`, `api`, and `worker` as the expected Gradle modules.
- `get_project_dependencies`: inspect the IDE-resolved dependency graph.
- `search_symbol`, `get_symbol_info`, `find_usages`, and `get_symbol_definition`: primary Java/Spring code intelligence when JetBrains MCP provides the operation.
- `rename_refactoring`: primary Java/Kotlin project-wide semantic rename when available.
- `reformat_file`: IDE formatting after substantive edits when project formatting is configured.
- `build_project`: IDE-aware compilation after Gradle sync.
- `get_run_configurations` and `execute_run_configuration`: use existing focused application or test configurations.

For Java and Spring, run `get_file_problems` first and treat its framework-aware diagnostics as authoritative over LSP diagnostics. LSP remains available for a symbol operation that JetBrains MCP does not expose or cannot complete, and must still be used where the harness requires an available language server for references or refactors. Never use LSP diagnostics as a substitute for the required JetBrains per-file inspection.

## Compile floor

After inspections, compile or run the focused checks through the checked-in wrapper:

```powershell
.\gradlew.bat :core:compileJava :api:compileJava :worker:compileJava
```

For a complete foundation check:

```powershell
.\gradlew.bat clean check --no-daemon
```

Compilation is authoritative for Java type errors but not sufficient for runtime behavior. A clean IDE inspection is also not sufficient by itself.

## Fallback when JetBrains MCP is unavailable

1. State that IDE semantic inspection is unavailable; do not claim an IDE-clean result.
2. Run the focused Gradle compile/check task.
3. Parse or validate changed YAML, JSON, and XML with an appropriate project tool where available.
4. Inspect changed configuration keys against the owning framework's current documentation.
5. Continue with the required runtime smoke test.

Do not substitute broad text search for semantic symbol analysis.

## MemoryOS-specific boundary checks

Static verification must preserve these repository invariants:

- Gradle modules remain flat: `core`, `api`, and `worker`.
- `core` never depends on `api` or `worker`.
- The seven Spring Modulith capabilities remain `identity`, `organization`, `authorization`, `knowledge`, `ingestion`, `retrieval`, and `assistant`; audit returns only with an evidence consumer under ADR 0003.
- Capability-owned persistence stays under that capability's `persistence` package and is not imported by another capability.
- Provider integration packaging is not decided by MEM-5; do not add speculative provider rules.
- API and worker remain thin composition roots.

Run `ModulithArchitectureTest` and `CoreDependencyRulesTest` whenever module declarations, package placement, or dependency rules change.

## Failure handling

- `No exact project is specified`: pass the absolute current MemoryOS repository root as `projectPath`.
- Connection refused: confirm IntelliJ IDEA and its MCP plugin are running, then verify `.omp/mcp.json` uses the current local SSE port.
- Empty result for an invalid or unknown file: confirm the IDE opened MemoryOS as a standalone Gradle project and imported the module; an empty result from the wrong project is not proof.
- Stale results after build-file changes: wait for Gradle sync or call `build_project`, then inspect again.
- Tool schema error: re-read `xd://mcp__jetbrains_<tool>` and retry with the exact mounted schema.

## Completion gate

Before claiming a MemoryOS change complete:

- Every changed IDE-supported file was inspected with warnings enabled, or IDE inspection unavailability was explicitly recorded.
- No unresolved IDE errors remain.
- The affected modules compile.
- Architecture tests pass when boundaries changed.
- The actual changed runtime surface was exercised: API endpoint, worker startup, CLI interaction, or focused behavior.
- No MCP configuration contains credentials.
