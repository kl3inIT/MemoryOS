# SWD392 Visual Paradigm MCP

A clean-room Gradle project for creating the UML diagrams required by the
SWD392 practical exam in Visual Paradigm 18.1:

- System Context diagrams (native level-0 Data Flow Diagram notation)
- Class diagrams
- Sequence diagrams
- Activity diagrams
- State diagrams

## Runtime architecture

Visual Paradigm 18.1 embeds Java 11, while Spring Boot 4.1 and Spring AI 2.0 run
on Java 17 or newer. This project therefore uses two processes:

1. `vp-bridge-plugin` is a Java 11 plugin loaded by Visual Paradigm. It exposes
   the Visual Paradigm OpenAPI operations only on `127.0.0.1:2026`.
2. `mcp-server` is a Java 25 Spring Boot application. It exposes the official
   Spring AI Streamable HTTP MCP endpoint on `127.0.0.1:2027/mcp` and delegates
   tool calls to the bridge.

Spring AI is intentionally not loaded into Visual Paradigm's Java 11 process.

## Build

Requirements:

- Visual Paradigm 18.1 installed at `C:\Program Files\Visual Paradigm 18.1`
- JDK 25

```powershell
.\run.ps1 test
.\run.ps1 package
```

Override the Visual Paradigm installation when necessary:

```powershell
.\run.ps1 package -PvpInstallDir="D:/Apps/Visual Paradigm 18.1"
```

The plugin ZIP is written to
`vp-bridge-plugin/build/distributions/`. The Spring AI executable JAR is written
to `mcp-server/build/libs/`.

For the OpenAI agent and prompting decisions, see
[`docs/spring-ai-best-practices.md`](docs/spring-ai-best-practices.md) and
[`prompts/diagram-agent-system.md`](prompts/diagram-agent-system.md).

## Run

1. In Visual Paradigm, choose **Help > Install Plugin > Install from a zip of
   plugin**, select the generated ZIP, and then restart Visual Paradigm.
   For an already installed development copy, close Visual Paradigm and run
   `.\gradlew.bat :vp-bridge-plugin:installPlugin`; it updates
   `%APPDATA%\VisualParadigm\plugins\vn.edu.swd392.vpmcp`.
2. Open or create a Visual Paradigm project. The bridge starts on
   `http://127.0.0.1:2026`.
3. Start the MCP sidecar:

   ```powershell
   .\run.ps1 sidecar
   ```

4. Configure the OpenAI-powered MCP client with the Streamable HTTP endpoint:

   ```json
   {
     "mcpServers": {
       "visual-paradigm": {
         "url": "http://127.0.0.1:2027/mcp"
       }
     }
   }
   ```

The MCP sidecar discovers the available tools when it starts, so Visual
Paradigm must be running first. For a sidecar-only smoke test, set
`VP_BRIDGE_REQUIRED=false`.

The two supplied repositories were reviewed as references; adopted and rejected
parts are documented in
[`docs/reference-review.md`](docs/reference-review.md).

## What still requires a person?

The one-time/manual bootstrap is:

1. Install the plugin ZIP and restart Visual Paradigm.
2. Open the intended `.vpp` project before starting the sidecar.
3. Provide the exact problem statement and select the diagrams required by that
   paper.
4. Review UML meaning and readability, then approve the saved submission.

After bootstrap, MCP can automate the repeatable path: extract a typed plan,
create native System Context/Class/Sequence/Activity/State elements, connect
and lay them out, inspect the result, repair concrete omissions, and save the
project.

For a System Context Diagram, `createContextDiagram` accepts one strict
declarative specification and supports a no-mutation dry run. It creates one
central circular `DFProcess`, rectangular `DFExternalEntity` nodes distributed
around all four sides, and named directed `DFDataFlow` connectors. Inspection,
semantic validation, optimistic revisions, targeted layout/routing, and routing
repair are separate tools. Native `DFDataStore` elements are rejected because
they are outside this level-0 boundary model.

It is deliberately not a fully autonomous replacement for the SWD lifecycle.
The agent must not invent missing requirements, decide disputed domain rules, or
submit an unreviewed diagram. Requirements validation, UML-semantic approval,
traceability to the paper, and final submission remain human gates.

For the supplied SWD392 submission template, the required deliverables are
diagram images plus brief explanations, not the `.vpp` working project. Use
`exportDiagramImage` for one diagram or `exportAllDiagrams` for a batch export.
The bridge supports PNG, transparent PNG, JPG, SVG, PDF, and TIFF through Visual
Paradigm's own OpenAPI exporter.

## Build the Word submission

With Visual Paradigm open on the completed `SWD392.vpp` project, run:

```powershell
.\submission.ps1
```

Because Visual Paradigm Evaluation Copy adds a watermark to native exports, the
default workflow expects three clean screenshots at:

- `build/submission-assets/manual-q1-class.png`
- `build/submission-assets/manual-q2-sequence.png`
- `build/submission-assets/manual-q3-state.png`

The script copies the supplied Word template, fills its metadata, inserts each
image and a brief explanation in the correct question, and keeps the original
template unchanged. Re-run with `-Force` when replacing an earlier generated
copy.

Use `-ClassImagePath`, `-SequenceImagePath`, and `-StateImagePath` to supply
different image locations. `-UseNativeExport` is only appropriate on a licensed
Visual Paradigm installation that produces watermark-free exports.

Use `-WithoutImages` to generate a pre-filled Word copy with the three image
slots left blank for manual pasting:

```powershell
.\submission.ps1 -WithoutImages -Force
```

Override `-TemplatePath`, `-OutputPath`, the three diagram-name parameters, or
`-ExamCode` for another paper. The default output keeps `ID` in the filename
until the student's real ID is supplied.
