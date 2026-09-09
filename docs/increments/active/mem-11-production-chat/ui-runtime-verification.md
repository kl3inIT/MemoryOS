# Phase 2.4 — Frontend runtime comparison

Date: 2026-09-09. Historical framework comparison supporting the accepted frontend integration. These results retain their probe boundaries; product implementation and acceptance are recorded separately in [verification](verification.md#phase-24--2026-09-09).

## Recommendation

Selected `useChatRuntime` from `@assistant-ui/ai-sdk` with a MemoryOS implementation of the public AI SDK `ChatTransport` interface. The libraries accumulate message parts and project tool states. MemoryOS retains authorization, send identity, server outcome handling and replay/history recovery. The implementation follows this ownership boundary; the table below records the earlier comparison, not current acceptance status.

Keep Phase 2.3's POST/202 reservation and separate GET stream. Do not add a Node server or move model/tool execution out of Embabel/Spring AI. No backend wire change is necessary merely to select this runtime.

The product Stop action requests backend cancellation and keeps the reader alive. Only a committed CANCELED outcome invokes native runtime cancellation; COMPLETED remains complete if it won the race. Disconnect only releases a reader. The native cancel button alone does not implement this contract.

The earlier standalone `ChatConversation` draft should not become a second message runtime. Retain only the product-specific policies required by transport/history integration, rather than another message/tool reducer.

## Source references

| Reference snapshot | Observed path | What to reuse |
| --- | --- | --- |
| Onyx `06aa2b09c` | `web/src/app/app/services/lib.tsx` uses fetch; `lib/search/streamingUtils.ts` reads newline-delimited JSON via getReader/TextDecoder; `hooks/useChatController.ts` and `stores/useChatSessionStore.ts` own lifecycle/tree state with Zustand | Learn behavior; do not assume an AI SDK runtime. The helper name includes SSE, but the inspected parser expects JSON lines |
| Onyx, same checkout | `hooks/useChatSessionController.ts` restores history, replays an active run, guards current-session writes and settles from persisted history; `useChatController.ts` calls a dedicated Stop endpoint | Background execution, server-authoritative outcome, safe navigation and replay fallback |
| OrgMemory local HEAD `db999fab` | `apps/web/src/features/assistant/api/chat-transport.ts` constructs DefaultChatTransport; `components/assistant-page.tsx` uses useChat, throttling, custom activity data and history hydration through setMessages | Native AI SDK frontend on a Java backend; this path does not use assistant-ui |
| OrgMemory, same checkout | `apps/api/.../assistant/UiMessageStream.java` emits UI Message Stream through Flux/ServerSentEvent; AssistantController sets the v1 protocol header and assistant ID | Its small frontend transport relies on Java emitting the matching protocol. Copying that transport alone cannot read MemoryOS's named events |

OrgMemory's inspected Stop wrapper calls `stopChat()`. Its transport has no resume configuration. This read does not establish background execution/durable Stop/replay parity with MemoryOS.

## Harness and receipts

Ignored scratch: `.tmp/mem11-ui-runtimes/`. Actual installed runtimes/hooks and transport parsers execute against loopback HTTP fixtures, in React/jsdom and headless Chromium. Model/tool execution, transcript authority and authorization decisions are synthetic. No product Java, IAM or database runs in this harness.

Exact direct versions: assistant-ui/react 0.15.18, assistant-ui/ai-sdk 0.0.4, assistant-ui/react-markdown 0.14.14, ai 7.0.94, ai-sdk/react 4.0.97, assistant-stream 0.3.41, React/ReactDOM 19.2.8, Vitest 4.1.11, Playwright 1.62.1, Vite 8.2.1, TypeScript 7.0.2, pnpm 11.22.0. The scratch lockfile pins transitive versions; these AI SDK dependencies are not installed in the product.

Final combined run: **75 tests passed in five files**, 39.78 seconds. These include characterization assertions that verify gaps, not 75 claims that the unmodified runtimes satisfy product requirements. Native integration/transport source typecheck passed. Three browser cases recorded no page errors.

| Suite | Cases | Boundary |
| --- | ---: | --- |
| Runtime comparison | 53 | Native hooks, real HTTP, synthetic text/tools/history/auth |
| AI SDK public extensions | 6 | Terminal guard, cleanup, confirmed Stop observation, user ID reconciliation, rich replay |
| Native decoder/accumulator alternative | 6 | Whether ExternalStore can reuse all rich-part handling |
| Current wire shape bridge | 7 | POST/202 + named SSE through public ChatTransport; fixture HTTP server |
| Browser | 3 | One per runtime: composer, IME, markdown/code, copy, tools, narrow viewport, fresh conversation |

Reproduce from scratch directory: run `corepack pnpm@11.22.0 dev` on loopback port 4187, then `corepack pnpm@11.22.0 test`. Tests allocate ephemeral fixture-server ports. Typecheck:

```text
corepack pnpm@11.22.0 exec tsc --noEmit --target ES2023 --module ESNext --moduleResolution bundler --jsx react-jsx --skipLibCheck --lib ES2023,DOM,DOM.Iterable runtimes.tsx memory-transport.ts guarded-sdk.ts
```

Raw findings, logs and screenshots remain in scratch. Screenshots show an unstyled workbench, not the planned product layout. The 2000-delta burst establishes lossless delivery in this fixture; prototype payload sizes and timings are not a production performance ranking.

## Observed differences

| Contract | AI SDK runtime | Assistant Transport | External Store |
| --- | --- | --- | --- |
| Two turns and server assistant IDs | Native; optimistic user ID needs reconciliation | Server state/converter | Application state |
| Parallel tools returning in reverse order, reasoning, two citations, tool failure | Native part accumulation/conversion | Backend supplies projected parts through state operations | Application reducer supplies projected parts |
| Approval state, unregistered tool names, files and persistent data | Supported in tested projection | Supported by supplied state | Supported by supplied reducer |
| EOF without terminal | Default transport allowed complete/unknown in the fixture; needs terminal guard | Strict decoder errors; server-status converter keeps pending state | Application must implement EOF policy |
| Native Stop | Disconnects and marks UI canceled before server confirmation | Disconnects; callback must request/read server outcome | Callback owns cancellation |
| Server abort frame alone | Tested projection became complete/unknown; insufficient for durable CANCELED | Server state expresses outcome | Application state expresses outcome |
| Completed history, partial replay, reload + full replay | Text/tools/sources restored without extra POST | State restored without extra POST | State restored without extra POST |
| Missing replay | Error hook; product history/wait policy still required | Resume endpoints plus product history/wait policy | Application recovery policy |
| Unmount during response | Default reader remained open; explicit public stop/abort cleanup closed it | Reader closed in fixture | Explicit cleanup closed it |
| 403 | Error surfaced; host clears private state | Same boundary | Host also resets optimistic running state |

Assistant Transport's pending state depends on a converter that uses backend status rather than only `connectionMetadata.isSending`. None of the runtimes enforces IAM, database terminal authority, retention or one active turn per session by itself.

Assistant Transport is suitable when agent presentation state and custom commands are themselves needed. Here it would move projection into an additional backend state/command protocol. Embabel usage alone does not establish that need. External Store is a valid fallback if supported native adapters cannot express required ownership; a pre-existing complete store would make it more attractive than building one now.

## Public integration against the current wire shape

`memory-transport.ts` implements `ChatTransport.sendMessages` and `reconnectToStream` and uses the generated SSE parser. Its loopback HTTP fixture returns the same ID/event shapes as Phase 2.3, not an actual Spring response.

Verified:

1. POST/202 supplies server user/assistant IDs. `useAISDKChat().setMessages` reconciles the optimistic user ID; the start chunk supplies the assistant ID.
2. Named text events become UIMessageChunk values. The library accumulates the message; the transport keeps no separate text/tool store.
3. Duplicate sequence does not duplicate text. Gap/reset/EOF produces an incomplete stream, never successful completion.
4. A Stop POST leaves the UI running until the server settles.
5. Committed CANCELED triggers a callback to public `aui.thread.cancelRun()`, preserving partial text and canceled status.
6. COMPLETED remains complete when it wins before Stop; the fixture records one inference.

No private runtime method was overridden. A separate DefaultChatTransport subclass also verified that its protected response-stream method can add terminal validation while keeping native parsing, if a later approved Java edge emits UI Message Stream directly.

The minimal bridge does not implement production polling, persistent ambiguous-send recovery, full branch/history adapters, connection bounds or every navigation race. Its line count is not a production size estimate. Rich tool cases used fixture candidate protocols: Phase 2.3 has not gained tool events from this probe.

## Additional reuse check

`UIMessageStreamDecoder` + `AssistantMessageAccumulator` was tested as an alternative to an ExternalStore reducer. Text, tools, sources and strict EOF worked. The accumulated fixture message did not retain the approval ID or persistent custom data part with the tested versions. Additional mapping would be needed; this combination is not certified as a complete replacement for the AI SDK path.

## Acceptance identified by the probe

The following list records the work identified before implementation. See the current [plan](plan.md#24--ui-thật-và-nghiệm-thu-phase-2) and [product verification](verification.md#phase-24--2026-09-09) for completed work and remaining scope.

- IAM/CSRF, same-Actor authorization revision changes, logout/private-state purge and stale response isolation.
- Generated service calls against Spring MVC/PostgreSQL, real model send/Stop and actual durable outcomes.
- Bounded replay/history fallback, send gating while the server remains RUNNING, failed Stop, deadlines and cleanup.
- Real thread/history/branch integration. Approval display is tested; approval execution, frontend tools, MCP and deep research are not.
- Product scrolling and layout, physical mobile keyboard behavior. Chromium narrow-viewport composer visibility is a narrower check.
- Promote relevant contracts into product tests and run repository/browser gates. The earlier implementation draft and this comparison are not product completion.

## Documentation checked

Context7 and installed package source were used together:

- [AI SDK runtime](https://www.assistant-ui.com/docs/runtimes/ai-sdk/v7)
- [Assistant Transport](https://www.assistant-ui.com/docs/runtimes/custom/assistant-transport)
- [External Store](https://www.assistant-ui.com/docs/runtimes/custom/external-store)
- [AI SDK transport](https://ai-sdk.dev/docs/ai-sdk-ui/transport)
- [Resumable streams](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams)

This recommendation changes neither Embabel ownership nor the accepted Onyx execution/persistence baseline.
