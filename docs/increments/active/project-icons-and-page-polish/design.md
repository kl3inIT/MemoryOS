# Project icons and project page polish

## Requirement

Owner report 2026-09-22, four UI corrections on Projects:

1. The selected project row in the sidebar must not show a focus ring.
2. The project page lists at most five recent conversations; "Xem thêm" loads the next five.
3. Project files must not render as an always-open list; they sit behind a collapsible group.
4. The project editor offers a topic icon next to the name field, like Onyx; the icon persists and
   shows in the sidebar, the projects list and the project header.

## Design

`chat_project` gains a nullable `icon_name` column (V119) holding the same lowercase key vocabulary
as Persona icons (`[a-z0-9-]{1,40}`). `ProjectInput`/`ProjectView` carry `iconName`; on update a null
`iconName` keeps the current icon (matching the Persona contract) so file-only updates from the
library never clear it, while a blank string clears it.

The web editor embeds a small icon button inside the name input that opens the shared `agentIcons`
grid in a popover; an empty choice restores the plain folder. `ProjectIcon` renders the stored key
or the folder fallback everywhere a project is shown.

The sidebar project link drops its `focus-visible` ring (the row still shows selection through the
sunken background). `ProjectConversationList` pages at five sessions. `ProjectFiles` wraps its list
in a `Collapsible` whose trigger shows the file count; the "Thêm tệp" picker stays outside the
collapsible content.

## Reuse

- `agentIcons`/`agentIconTones` and the popover grid pattern from the agent editor.
- `Collapsible` from `components/ui`, matching `source-run-history.tsx`.
- Persona `iconName` validation and keep-on-null update semantics.
