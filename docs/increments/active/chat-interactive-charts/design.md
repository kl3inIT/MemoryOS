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

1. **Capture without Jupyter.** The MemoryOS executor runs a plain Python process, not a kernel, so there is no display hook. `sitecustomize.py` registers an `atexit` handler (`memoryos_charts.capture`) that, only when the run imported `matplotlib.pyplot`, saves each still-open figure (at most 10) as `.memoryos-charts/chart-{n}.png` and, when the axes are recognised, `chart-{n}.json` (at most 256 KiB). A figure the code closed is not captured; a figure the code saved and left open is captured too, as in E2B. A capture failure is written to stderr and never changes the exit code.
2. **Owned extraction code.** `e2b-charts` 1.0.0 requires numpy 2 while the executor pins numpy 1.26, and it only uses `numpy.datetime64`, so its source is copied into `interpreter/executor/memoryos_charts` under its MIT license and owned by MemoryOS from then on (owner request 2026-09-17). Verified with the executor's matplotlib 3.10.9, numpy 1.26.4 and pydantic 2.11.9.
3. **Transport.** The service is unchanged: the reserved directory comes back in the workspace snapshot like any file. `RunPythonTool` keeps `.memoryos-charts/` entries apart from the model's files, stores each PNG as a `chat_file_artifact` with its chart JSON (validated as an object with a `type`), and deletes every service copy.
4. **Model result.** The tool JSON gains `charts: [{title, type, file_link}]` (`type` is `image` when there is no chart data); the data points stay out of the model context.
5. **Persistence and reads.** V72 adds a nullable, bounded `chart jsonb` column. Generated files on history and on `code` events carry only `chart: true|false`, because a chart can be 256 KiB and the replay buffer is bounded; the browser reads the data from `GET /api/chat/file-artifacts/{id}/chart` with the content route's owner authorization.
6. **Rendering.** The browser renders `line`, `scatter`, `bar`, `pie` and `box_and_whisker` with the repo's shadcn chart component (Recharts), in a card below the answer with an Interactive/Static toggle; `superchart` renders its subcharts; `unknown`, invalid data and dates encoded as numbers fall back to the PNG. The PNG stays the download.
7. **Validation.** The web schema validates the chart with zod and bounds elements (1 000 points per series, 20 series) before rendering.

## Security and limits

- Chart JSON is data only; labels render as text. No HTML, SVG or script from the sandbox reaches the DOM.
- The extraction code is part of the image; the executor stays network-less.
- The capture adds at most 10 PNG renders at exit; the per-call timeout still bounds the run.

## Out of scope

Plotly or other libraries, DataFrame tables as structured results (the answer can use `render_gui`), editing a chart, charts from `render_gui`.
