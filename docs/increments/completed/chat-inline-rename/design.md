# Inline conversation rename

## Accepted scope

While a conversation is renamed, its row is a single name field with no Save or Cancel buttons. Frontend only: the rename request, API, spec of the title and backend are unchanged.

## Behavior

- Enter or leaving the field saves the trimmed name through the existing `renameChatSession` mutation (or the thread list's own `rename`).
- An empty or unchanged name closes the editor without a request. The field no longer carries `required`, because an empty name is a quiet cancel rather than a validation error.
- Escape discards the draft and returns focus to the row.
- A failed save keeps the editor open with the draft and the existing error alert, so the next Enter or blur retries.
- Enter and Escape return focus to the row only while focus is still in the field. When the person left the field by clicking or tabbing elsewhere, focus stays at that destination.
- Enter that confirms an IME candidate (`isComposing`) is ignored.

## Design decisions

- A synchronous `submitting` ref guards the write. State such as `isPending` updates after render, so Enter followed by the blur that the closing field causes would otherwise send two requests. Escape sets the same ref so a blur during unmount cannot save the discarded draft.
- While a save is pending the field is `readOnly` with `aria-busy`, not `disabled`: a disabled input loses focus, which would break the focus rule above.
- The shared `Input` keeps its focus indicator and the accessible name "Tên hội thoại". The now-unused icon buttons, their imports and the two catalog entries ("Lưu tên hội thoại", "Hủy đổi tên") are removed.
- No new component or abstraction: `ChatSessionRenameForm` stays private to `chat-session-row.tsx`.
