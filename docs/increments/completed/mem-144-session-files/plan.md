# MEM-144 plan — files in this conversation

- [x] **Filter.** `sessionId` on `GET /api/chat/library`: artifacts by `session_id`, uploads through the conversation's own `chat_message.files`, both requiring the caller to own a non-deleted conversation; `openapi.yml` and the generated client.
- [x] **Panel.** A file button on the conversation header opens a right-hand sheet listing the conversation's files with preview, download and confirmed deletion; vi/en keys.
- [x] **Tests.** `ChatPersistenceIntegrationTest.aConversationsOwnFilesAreItsArtifactsAndTheUploadsAttachedInIt`, the API case and `chat-session-files.test.tsx`: Java: the filter returns the conversation's artifacts and the uploads attached in it, excludes another conversation's files, and returns nothing for a conversation the caller does not own. Web: the panel lists the files and opens the preview.
- [x] **Docs.** Chat spec paragraph, verification matrix rows, `AGENTS.md` active increment entry.
- [ ] **Evidence.** The listed tests pass locally (2026-09-20); the full `:core:test`/`:api:test` runs, the web suite and a staging check on a conversation with an upload and a generated file.
