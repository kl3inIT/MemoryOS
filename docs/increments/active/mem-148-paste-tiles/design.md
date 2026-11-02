# MEM-148 — Large pastes collapse into composer tiles

## Problem

Pasting a long block (a log, an email, a table) into the Chat composer fills the field: the question the
person is about to type is pushed out of sight, and the field grows to its maximum height. Onyx offers
Settings › Chat Preferences › *Collapse Large Pastes*: "When pasting text longer than 3 lines or 200
characters, collapse it into a compact tile instead of inserting it inline", stored as
`user.preferences.paste_as_tile` and off by default.

## Decision

1. **A personal preference, off by default.** `chat_preferences.paste_as_tile` (V197, `BOOLEAN NOT NULL
   DEFAULT FALSE`) rides on `ChatPreferences` and `GET/PUT /api/chat/preferences` as `pasteAsTile`. A
   missing row reads as off, as Onyx. In `PUT` the member is optional and an absent value is off, so an
   older client that does not know it saves the default rather than failing. Settings › Chat shows it as
   *Thu gọn đoạn dán dài* with the existing `SettingRow` + `Switch`, saved on change. The switch ships
   with the behaviour ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
2. **The threshold is Onyx's**: a paste is large when it has more than 3 lines or more than 200
   characters. Lines are counted after dropping one trailing line break, so a three-line block copied
   with its final newline stays inline.
3. **Tiles sit outside the field**, in the composer's attachment row after the files, inside the same
   two-row `ClampedList` so a draft with many tiles keeps to two rows. The field stays assistant-ui's
   `ComposerPrimitive.Input` textarea: no rich-text editor and no placeholder token in the text. A tile
   reuses the attachment tile's shape (`rounded-xl bg-muted`, icon square, name and a secondary line):
   *Đoạn dán* and the line count. Clicking it opens the project's `FormDialog` with a `Textarea` to read
   and edit the text; saving an emptied text removes the tile. Its × removes it.
4. **A paste with files is left to assistant-ui**, which turns clipboard files into attachments; only a
   text paste is collapsed.
5. **Sending appends the tiles' text** to the typed text in paste order, each part separated by one
   blank line, as plain text. MemoryOS has no fencing convention for pasted text and a fence would change
   what the model reads (a table pasted from a spreadsheet is not code), so nothing is wrapped. The
   message stored and shown is that joined text; the server is unchanged. Both send paths join: Send
   (which assistant-ui would otherwise keep disabled while the field is empty) and Enter (the form's
   submit). The tiles are cleared once the text is handed to the composer.
6. **The 32,000-character message limit** (`ChatCommand`) applies to the joined text. When it is
   exceeded Send is disabled and an alert under the field says so, as the twenty-file alert does. The
   field's own `maxLength` still bounds the typed part.
7. **Tiles survive a reload like the draft.** `ChatComposerDraft` keeps the typed text in
   `sessionStorage` per actor and conversation; the tiles are kept the same way under their own key, so
   a reload of the tab restores both and nothing outlives the tab.
8. **Voice Auto-Send waits while tiles are on the draft**, as it waits for an unready file, so a spoken
   question is never sent without the pasted text it refers to; the person sends with Send.
9. **Where it applies.** The behaviour lives in `ChatThreadComposer`, which every Chat composer shares:
   the main Chat, an assistant's *Chat thử* and *Agent Builder* tabs. Following the preference there is
   free, so all of them do. A shared, read-only conversation has no composer. The channel direct
   assistant setting is a picker, not a composer.

## Out of scope

- Collapsing a long sent user message behind *Xem thêm*: the bubble renders assistant-ui message parts
  and a clamp with a measured overflow is not trivial; left for a later change.
- Turning a tile back into inline text, reordering tiles, or tiles in the edit-message composer (an
  edited message is edited as text).
- A server-side notion of a paste: the message carries only text.
