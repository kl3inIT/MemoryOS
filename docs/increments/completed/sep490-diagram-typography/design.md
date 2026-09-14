# SEP490 diagrams and MCP typography

Scope: MEM-86 context and primary business flows in Vietnamese, maintained in `docs/academic/sep490/diagrams/MemoryOS.vpp`.

The context uses two human responsibilities (Người dùng, Quản trị hệ thống), Keycloak, external source systems and model providers. Connector remains inside MemoryOS. Dynamic authorization is checked per operation through current Group grants; actor labels do not define fixed account types.

The independent Visual Paradigm plugin adds diagram typography controls and measures flow captions at the selected font size to prevent truncation. Native diagram semantics are preserved. Editable VPP and four current previews are retained; temporary exports and application backups are excluded.

Application CI ignores changes limited to `docs/**`, root Markdown documents and `tools/visual-paradigm-mcp/**` for both main pushes and pull requests. Mixed application/configuration changes still run CI. Automatic staging deployment depends on successful CI, so an ignored change does not initiate deployment. The desktop tool remains an independent build verified locally.
