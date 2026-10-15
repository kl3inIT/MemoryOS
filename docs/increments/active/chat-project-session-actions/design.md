# Project conversation actions

The sidebar Project menu gets a *Hội thoại mới* item next to *Xem dự án*. It navigates to `/projects/{projectId}`, whose page already holds a fresh, unsent conversation bound to the Project (`ChatThreadController.setProject`). No session is created by the menu: the session is created by the first send through `transport.projectId`, as for any Project conversation. An empty history entry would otherwise appear for a conversation the person may never start ([the Project spec](../../../specs/chat.md) keeps "no empty session is created merely by opening a project or typing"). There is therefore no pending state and no failure notification for the item.

The item and *Xem dự án* end on the same page. The item exists because starting a conversation and viewing a Project are different intents in the menu; when the person is already on `/projects/{projectId}` the unsent thread, and any draft in it, is kept.

Removing a conversation from a Project must preserve its transcript, its route and its place in *Hội thoại gần đây* and history search. Source inspection found that membership mutation responses updated React Query and reloaded the thread list, but did not update the mounted `ChatThreadController` when the move started from a sidebar row. `useRefreshChatSessions` now accepts the authoritative returned session as well as an id: it stores the session in its cache entry, updates the mounted controller by remote id, then invalidates and reloads the lists. The Project row's drag and drop and the conversation menu's move both go through it, which replaces the manual cache write each of them had.

A removed row unmounts the menu button that held focus, so focus falls to the page. `ChatSessionRow` continues at the retained history link (`data-chat-session-id`) after the change, only when focus did fall to `body`, so a person who moved focus elsewhere is not pulled back.

Scope: frontend actions, synchronization and regression coverage; the existing API and persistence contracts remain authoritative.

The original disappearance was not reproduced in synthetic browser scenarios: existing sessions and first-send sessions retained their history even before the synchronization fix. The checks establish a regression contract, not evidence that deployed data was deleted or recovered.
