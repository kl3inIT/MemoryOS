# Plan and verification

- [x] Create Vietnamese context and three primary business flows.
- [x] Resize shapes, enlarge fonts and route context labels.
- [x] Fix MCP caption sizing; build and restart the installed plugin.
- [x] Run plugin check successfully and inspect native exported context.
- [x] Native context validator: one process, five external entities, twelve flows, zero violations.
- [x] Retain canonical project/previews and exclude generated backups/locks.

IDE inspection cannot resolve the independent nested build dependencies in the main project; compilation against installed Visual Paradigm OpenAPI and plugin tests pass. PNG previews carry the Visual Paradigm Evaluation watermark. These are review diagrams, not instructor acceptance.

This scoped direct-to-main commit uses `[skip ci]`: application CI and its downstream staging deployment are not needed for diagrams and the independently verified desktop tool.
