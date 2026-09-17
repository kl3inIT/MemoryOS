# Interactive charts plan

- [ ] **Executor.** Pin `e2b-charts`; `sitecustomize.py` atexit capture of open figures (PNG + chart JSON, 10 figures, 256 KiB JSON); unit test in the executor and an integration test through the service with a line, bar, pie and an unknown chart; measure image size and exit time.
- [ ] **Service.** `charts` in the execution result and stream `result` event; reserved directory excluded from `files`; tests.
- [ ] **Java.** `InterpreterClient` parses `charts`; `RunPythonTool` downloads PNGs, stores them with the chart JSON (migration: nullable bounded column on `chat_file_artifact`), adds `charts: [{title, type}]` to the model result; `generatedFiles[].chart`; OpenAPI.
- [ ] **Web.** zod chart schema with bounds; chart card with Recharts via the shadcn chart component and Interactive/Static toggle; PNG fallback; tests with Vietnamese labels.
- [ ] **Docs.** Chat spec, verification matrix, MEM-110 plan link.
- [ ] **Evidence.** Local tests; staging prompt that draws a Vietnamese revenue line chart and a pie chart.
