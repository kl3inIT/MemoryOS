# MEM-144 — Files in this conversation

[MEM-144](https://linear.app/memory-os/issue/MEM-144) · depends on [MEM-142](../mem-142-file-library/design.md)

## Problem

The library answers "where are my files"; it does not answer "what did this conversation produce". Someone who uploaded a spreadsheet twenty answers ago, or whose code run wrote three files, has to scroll the transcript to find them again.

## Reference

Onyx `f9e3de36c8` has `GET /projects/session/{chat_session_id}/files`, but it returns the files of the *project* the session belongs to, not the files of the conversation, and returns an empty list for a session without a project. It is the wrong shape for this panel, so this increment follows the issue's WorkBuddy reference and the MEM-142 surfaces instead of porting that route.

## Decisions

1. **The library endpoint gains a `sessionId` filter; no second route.** `GET /api/chat/library?sessionId=…` narrows the same union to one conversation, so the panel reuses the paging, sorting, filters, `usedBy` labels and totals that already exist. Every other parameter keeps working, so the panel can sort or filter within the conversation.
2. **A conversation's files are its artifacts plus the uploads attached in it.** Artifacts carry `session_id` (V90). An upload carries no conversation, so the upload branch matches through the conversation's own messages: `chat_message.files` holds the descriptors, and the filter reads them for that one session, which needs no index because it is scoped to a single session's rows before the JSONB is expanded.
3. **The filter requires the caller to own the conversation.** The upload branch joins `chat_session` on the owner and a non-deleted conversation. Without that, an Agent-attached upload used in someone else's conversation would let its owner learn where it was used; the artifact branches are already owner-scoped.
4. **The panel is a right-hand sheet on the conversation header,** beside Share and the conversation menu, opened by a paperclip-counted button. It lists the conversation's files with the library's row actions — preview through the MEM-111 modal, download, confirmed deletion — but no "open the conversation" action, because it is already open. It does not paginate: a conversation's file count is small, and the first page (50) is the bound.

## Security

The filter narrows an already owner-private listing and adds an ownership check of its own for the upload branch; it grants no new access, and a conversation the caller does not own returns an empty list rather than an error, as the listing does for any filter that matches nothing.

## Out of scope

Attaching or removing a file from the panel, the per-conversation token count, and any change to the library page or to sharing.
