# Interactive charts from run_python

Related: [MEM-110](../mem-110-memoryos-interpreter/design.md), [MEM-111](../mem-111-generated-file-preview/design.md). Owner decision 2026-09-17: follow E2B; keep `render_gui` unchanged; do not add assistant-ui generative UI for charts.

## Problem

A chart `run_python` draws with matplotlib reaches the user only if the model saves a PNG, and then only as a static image. E2B's Code Interpreter returns the figure's data as well, so the client can draw an interactive chart (hover values, legend toggles) and keep the PNG as a fallback.

## Reference

- **E2B Code Interpreter** (`e2b-dev/code-interpreter`, MIT): the Jupyter kernel's IPython startup script registers a display formatter for MIME type `e2b/chart`. When a matplotlib figure is displayed, the formatter calls the `e2b-charts` package (MIT), whose `chart_figure_to_dict` walks the figure's axes and returns a typed chart next to the `image/png` result.
- **Chart model** (`e2b_charts`): `type` is `line`, `scatter`, `bar`, `pie`, `box_and_whisker`, `superchart` (several subplots) or `unknown`; every chart has `title` and `elements`. Axis charts add `x_label`, `y_label`, `x_unit`, `y_unit`, `x_ticks`, `x_tick_labels`, `x_scale`, `y_ticks`, `y_tick_labels`, `y_scale`. Elements are `{label, points: [[x, y]]}` for line/scatter, `{label, group, value}` for bar, `{label, angle, radius}` for pie and `{label, min, first_quartile, median, third_quartile, max, outliers}` for box plots.
- **E2B `ai-analyst`** renders the chart JSON with ECharts and falls back to the PNG for `unknown`. Known gaps reported upstream: dates on the x axis arrive as numbers (issue #104) and grouped bars can lose their group names (issue #125).
- **Onyx** has no structured chart result; it shows generated images.

## Decisions

1. **Capture without Jupyter.** The MemoryOS executor runs a plain Python process, not a kernel, so there is no display hook. The executor's `sitecustomize.py` registers an `atexit` handler that, for each still-open matplotlib figure (at most 10), writes `chart-{n}.png` and, when `e2b-charts` recognises it, `chart-{n}.json` into a reserved workspace directory. A figure the model closed or saved and closed is not captured twice. A capture failure is written to stderr and never changes the exit code.
2. **Transport.** The service reports the reserved directory's entries as a new `charts` list in the execution result (`{png_file_id, json}`), not as generated files, so they do not count against or appear among the model's files. The JSON is bounded (256 KiB per chart); larger or invalid JSON keeps only the PNG.
3. **Model result.** The tool JSON gains `charts: [{title, type}]` so the model can refer to them; the data points are not sent to the model.
4. **Persistence.** The PNG is adopted as a `chat_file_artifact` like other generated files; the chart JSON is stored on the same row (new nullable column, bounded) and returned with `generatedFiles` as `chart`. No new table.
5. **Stream and history.** `ChatCodeEvent` terminal stages carry the files with their `chart`; a reloaded conversation keeps them.
6. **Rendering.** The browser renders `line`, `scatter`, `bar`, `pie` and `box_and_whisker` with the repo's shadcn chart component (Recharts), in a card below the answer with an Interactive/Static toggle; `superchart` renders its subcharts; `unknown`, invalid JSON and dates encoded as numbers fall back to the PNG. The PNG stays the download.
7. **Validation.** The web schema validates the chart with zod and bounds elements (1 000 points per series, 20 series) before rendering.

## Security and limits

- Chart JSON is data only; labels render as text. No HTML, SVG or script from the sandbox reaches the DOM.
- `e2b-charts` is pinned in the executor lockfile; the executor stays network-less.
- The capture adds at most 10 PNG renders at exit; the per-call timeout still bounds the run.

## Out of scope

Plotly or other libraries, DataFrame tables as structured results (the answer can use `render_gui`), editing a chart, charts from `render_gui`.

## Open questions to verify during implementation

- Whether the service's workspace snapshot includes a hidden directory; otherwise the reserved directory needs a non-hidden name that the service excludes from `files`.
- `e2b-charts` compatibility with the pinned matplotlib 3.10.
