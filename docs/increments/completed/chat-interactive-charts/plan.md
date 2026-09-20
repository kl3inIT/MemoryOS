# Interactive charts plan

- [x] **Executor.** Own `memoryos_charts` (from `e2b-charts`); `sitecustomize.py` atexit capture of open figures (PNG + chart JSON, 10 figures, 256 KiB JSON); verified locally against the executor versions (line, pie, bar with Vietnamese labels). Still open: measure the added exit time in the real image.
- [x] **Service.** No change: the reserved directory returns in the workspace snapshot (integration tests `test_open_figures_are_captured_as_chart_data_and_png_at_exit`, `test_runs_without_pyplot_leave_no_chart_directory`, run in CI).
- [x] **Java.** `RunPythonTool` separates `.memoryos-charts/`, stores PNG + chart JSON (V72), adds `charts` to the model result; `generatedFiles[].chart` flag; `GET /api/chat/file-artifacts/{id}/chart`; OpenAPI.
- [x] **Web.** zod chart schema with bounds (`chat-chart.ts`); `ChatChartCard` with Recharts through the shadcn chart component and an Interactive/Static toggle; PNG fallback; `chat-chart.test.ts` and the e2e `draws captured charts interactively with the PNG as the static view`, screenshots reviewed (a categorical palette replaced the neutral `--chart-*` tokens, which could not tell series apart).
- [x] **Docs.** Chat spec, verification matrix, MEM-110 plan link.
- [x] **Evidence.** Local tests; staging prompt that draws a Vietnamese revenue line chart and a pie chart. (2026-09-20: owner acceptance on staging.)
