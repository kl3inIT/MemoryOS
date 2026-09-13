# Chat ThreadList runtime

Status: approved under the owner's 2026-09-13 goal ("start, commit each part"). Conversation archive was implemented and then withdrawn by the owner after UI review on the same day (see plan); conversations have no archived state.

## Goal

Move the Chat sidebar and conversation lifecycle onto assistant-ui's remote thread list instead of the page-local runtime plus React Query list.

Visible behavior must not regress:
- project folders and drag-and-drop;
- server history search;
- shared read-only page;
- project conversation lists;
- edit, regenerate, branches and feedback;
- resume of a running reply;
- model and Web choice;
- the initial short title derived from the first question.

## Verified library boundary (assistant-ui 0.15.18, core 0.3.17, ai-sdk 0.0.4)

**Host.** `useRemoteThreadListRuntime({ runtimeHook, adapter, threadId, onThreadIdChange })` is the available host. `useChatRuntime` wraps itself in `useRemoteThreadListRuntime` with `allowNesting: true`, so inside our list it collapses to a single thread runtime.

**No background threads.**
- `backgroundThreads` exists only on the `RemoteThreadList` store entry.
- That entry needs a per-thread AI SDK chat factory. `useChatThread`/`AISDKChatThread` are not exported, and `AISDKThreads` hard-wires the assistant-cloud adapter.
- **Consequence:** only the visible thread streams. Visited bodies stay mounted but close their reader; a switched-away reply keeps running on the server and is resumed when the thread is opened again, as today.
- Sidebar `isRunning` is live only for the visible thread.
- Revisit when ai-sdk exports a per-thread factory for custom adapters. Do not deep-import dist internals.

**Thread keying.** Thread bodies are keyed by the local thread id (`${id}:${generation}:${hookEpoch}`). Server-ID promotion after the first send therefore does not remount the composer or the stream.

**Initialization.**
- The external-store `append` synchronously triggers `threadListItem.initialize()` (through `RemoteThreadResource`) and then calls `onNew`, which runs the transport. It does not wait for initialization first.
- Initialization is deduplicated: the first call optimistically moves the item out of `new`, and later callers await the same `initializeTask`.

**Title generation.**
- After initialization, `generateTitle()` runs once, as soon as any non-running message exists. That already happens with the user message, mid-stream.
- `adapter.generateTitle` must return an `AssistantStream`. The core only updates local title state from it; it does not call `adapter.rename`.

**Switching.** A controlled `threadId` that is not in the loaded page is resolved through `adapter.fetch`. Every MemoryOS thread is reported as `regular`, so the library's archive transitions never occur.

**History.** `useAISDKRuntime` consumes the ambient `history` adapter from `unstable_useAdapters` through `withFormat(aiSDKV6FormatAdapter)`. It gates the thread with `isLoading` until `load()` resolves.

**Transport wiring.** The `__internal_setGetThreadListItem` hook is wired only for `AssistantChatTransport`. `MemoryOsChatTransport` is wired through our controller.

**Server naming.** `ChatTurnPersistence.claimTitle` returns empty, without claiming, while a reply is active or before a completed assistant message exists. A naming failure keeps the initial title.

## Design

### Runtime placement

`AppShell` renders `ChatNavigation` on every application page, so the list runtime is mounted once in the authenticated application boundary.
- The mount is `ChatRuntimeProvider`, inside `ApplicationSessionProvider`.
- It is keyed by actor, authorization version and capabilities, like the chat page today, so cached threads never cross identities.
- Pages outside Chat keep an empty new main thread, which makes no network request.
- `ChatPage` renders only the main thread's view.
- **Known cost:** the thread list is also loaded on admin/settings routes, including admin routes that do not render the chat sidebar. This is one bounded 30-item request, the same one the sidebar makes today on application pages.

### Adapter (`chat-thread-list-adapter.ts`)

There is a single stable adapter object per provider mount.

| Method | MemoryOS call |
| --- | --- |
| `list({after})` | `listChatSessions` with an offset cursor, 30 per page. Rows are `regular`. `custom` = `{updatedAt, createdAt, projectId, personaId, rootMessageId}`; `lastMessageAt` = `updatedAt`. |
| `fetch(id)` | `getChatSession`. |
| `initialize(localId)` | Awaits the thread controller's session deferred (see below) and returns its id. It never creates a session with a placeholder title. |
| `rename` / `delete` | `renameChatSession` / `deleteChatSession`. |
| `archive` / `unarchive` | Required by the adapter type; reject because the product has no archive and the UI never offers it. |
| `generateTitle(remoteId)` | Waits until the thread controller reports its first `ready` after an accepted turn. Then calls `generateChatTitle` (server-side, once, preserves manual rename) and emits the returned title as a one-part `AssistantStream`, as in the library's `LocalStorageThreadListAdapter`. The page no longer calls `generateChatTitle`. If the thread unmounts first, the next list reload shows the server title, and the server naming stays pending for a later explicit trigger. |
| History and attachments | Passed directly to `useAISDKRuntime` by the per-thread runtime hook, one instance per mounted thread. |

**History adapter.**
- `load()` returns `loadChatHistory(remoteId)`, mapped with `toUiMessages` into linear selected-branch `{parentId, message}` items.
- A thread without a `remoteId` resolves immediately with no messages and no network request.
- `append`/`update` are no-ops. **The server is the transcript authority; do not add client-side persistence.**
- A RUNNING reply is excluded from `load()` and resumed by the existing bridge.

### Per-thread controller

`runtimeHook` renders in the list's hidden tree, not under the page, so conversation state cannot stay in `ChatConversation` `useState`.

A `ChatThreadController` holds the transport, connection state, error, stopping, checking, unavailable, the model notice and a session deferred.
- It is created per local thread id in a small registry and read through `useSyncExternalStore`.
- `runtimeHook` creates it and composes `useChat` with `useAISDKRuntime` (history, attachments, `isSendDisabled`). The page reads the main thread's controller.
- The controller owns what `ChatRuntimeBridge` did: accepted-ID replacement, cancel, check/reload and resume.
- The list host keeps visited thread bodies mounted; a hidden thread closes its reply reader and reconciles from saved history when shown again.
- The controller is disposed when the thread body unmounts.

Session creation stays in the transport, where the first question text is available.
- On the first `sendMessages`, the transport creates the session with `initialChatTitle(text)` and the project read from the controller, as today.
- It then resolves the controller's session deferred, which completes `adapter.initialize`.
- A validation or creation failure rejects the deferred, and the library allows a later retry.
- An existing thread (`remoteId` present) resolves the deferred at construction.

The page header reads title, persona and project from `threadListItem` state and `custom`, not from a fresh `getChatSession`. Promotion therefore performs no session or history read (`chat.spec.ts` asserts this). Header setting changes reload the item through `adapter.fetch`.

### URL synchronization

- Route `/chat/$sessionId` sets `threadId`. `/` and `/projects/$projectId` set `undefined` (new thread). Other pages leave the current value untouched.
- `onThreadIdChange(id)` navigates to `/chat/$sessionId` with `replace` only while a chat route is active.
- Sidebar rows keep real links. Clicking navigates, and the controlled `threadId` switches. This replaces `view.key`/`promotedSessionId`.

### Sidebar

**Regular list.**
- The sidebar reads `threads.threadIds`/`threadItems` from the list state and keeps the existing Today/Yesterday/Earlier sections, grouped by `custom.updatedAt`. `ThreadListPrimitive.Items` cannot emit section headers, so the sections iterate the same state.
- A wrapper maps `threadListItem` to the `ChatSession` shape and renders the existing prop-driven `ChatSessionRow`, which project lists still reuse.
- Rename and delete go through `aui.threadListItem`, so list state updates optimistically.
- Share and move-to-project stay app-owned and reload the list.
- "Load more" uses `loadMore`.

**Search entry.** "Search conversations" is a sidebar row directly under "New conversation" (icon only when collapsed), following the Claude/v0 pattern reviewed on Mobbin, and Ctrl/⌘+K opens the same server search dialog. The "Recent conversations" heading no longer carries a search icon.

## Out of scope

Context display, background thread mounting, message images and further assistant-ui elements (item 3).

## Risks

- The provider move touches every authenticated page. E2E suites must cover `/`, a session, a project, search and settings.
- If `generateTitle` never observes `ready` (unmounted thread), the title remains the initial short title until another naming trigger; no data is lost.
