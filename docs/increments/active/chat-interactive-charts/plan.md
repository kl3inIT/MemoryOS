# Interactive charts plan

- [x] **Executor.** Own `memoryos_charts` (from `e2b-charts`); `sitecustomize.py` atexit capture of open figures (PNG + chart JSON, 10 figures, 256 KiB JSON); verified locally against the executor versions (line, pie, bar with Vietnamese labels). Still open: measure the added exit time in the real image.
- [x] **Service.** No change: the reserved directory returns in the workspace snapshot (integration tests `test_open_figures_are_captured_as_chart_data_and_png_at_exit`, `test_runs_without_pyplot_leave_no_chart_directory`, run in CI).
- [x] **Java.** `RunPythonTool` separates `.memoryos-charts/`, stores PNG + chart JSON (V72), adds `charts` to the model result; `generatedFiles[].chart` flag; `GET /api/chat/file-artifacts/{id}/chart`; OpenAPI.
- [ ] **Web.** zod chart schema with bounds; chart card with Recharts via the shadcn chart component and Interactive/Static toggle; PNG fallback; tests with Vietnamese labels.
- [ ] **Docs.** Chat spec, verification matrix, MEM-110 plan link.
- [ ] **Evidence.** Local tests; staging prompt that draws a Vietnamese revenue line chart and a pie chart.
