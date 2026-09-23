# Plan — project icons and project page polish

- [x] V123 migration: `chat_project.icon_name varchar(40)` nullable
- [x] `ProjectEntity.iconName`, `ChatProjectService.ProjectInput/ProjectView`, keep-on-null update
- [x] `openapi.yml` ProjectInput/ProjectView `iconName`; regenerate `web/src/lib/hey-api`
- [x] `projectSchema.iconName`; `ProjectIcon` + `ProjectIconPicker` in `chat-projects-page.tsx`
- [x] Sidebar `ProjectFolder`: remove focus ring, render `ProjectIcon`
- [x] `ProjectConversationList` page size 30 → 5
- [x] `ProjectFiles` list behind a `Collapsible` group with count
- [x] `tsc -b`, `oxlint`, `oxfmt` clean
- [ ] `clean check` (needs Docker for integration tests)
- [ ] Browser verification of the four surfaces
