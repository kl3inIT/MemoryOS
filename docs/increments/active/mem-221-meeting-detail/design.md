# MEM-221 Meeting detail page

Second fix batch of the 2026-10-03 UI critique: the meeting detail page, which the critique scored 24/40 and three later passes brought to 27/40. Frontend, plus one rule in how a voice's name is offered.

## Requirement and design

A meeting is read for an afternoon and corrected line by line, so what is read keeps the page and what describes the meeting stands beside it.

1. **Layout.** The page takes the wide page width. From 1280 px the meeting's panel (facts, timeline, voices and the names offered for them) always stands beside the transcript; below that it is a sheet opened from the shell header. The shell header carries the breadcrumb back to the list. The tabs, the search and the open tab's tools stay pinned under the recording bar from `md` up.
2. **Actions.** Sharing stands in the header; editing the details and deleting open from the "…" menu beside it. Deleting asks first.
3. **Transcript.** One row per line: the voice's badge in the margin, its name over what was said, the time at the right. Lines one voice says in a row read as one turn. A line reached from the timeline stops below what is pinned, and the subject marked as being read is the first line below it.
4. **Voices.** A voice is a filled badge with its number, or the initial of the name it goes by, in `speaker-1` … `speaker-6`. Orange and green are left out because the transcript marks words with them.
5. **Marks.** A stretch the transcriber was unsure of is orange and dotted; words the owner changed are green and underlined in the line they changed, and open to what was heard there and the way back. One row above the lines says what the two colours mean and takes every change back at once. Proposals of a pass wait folded above the transcript.
6. **Search.** A query typed without marks finds the words that carry them; one typed with marks asks for exactly those. `/` and Ctrl/Cmd+F reach the field.
7. **Address.** The open tab is the `tab` search parameter, so a reload or a shared link opens the same one. Naming the tab never moves the page.
8. **Marks of the reader.** A star shows as soon as it is pressed and is taken back if the server refuses it; a star or a bookmark that fails says so in a notification, since the page is as long as the meeting.
9. **Names offered.** "Name đây" and "Name xin phép" count as a self-introduction only for a listed participant: every sentence opens with a capital, and "Chạy đây" is not a person.

## Reuse

`SettingsLayout` and `PageHeader`, `Sheet`, `DropdownMenu`, `ConfirmDialog`, `Popover`, `Collapsible`, `HelpPopover`, `useActionNotifications`, `hoverReveal`, `@tanstack/react-virtual`. New tokens only for the voices' badges ([design tokens](../../../guidelines/design-tokens.md)).

## Exceptions to the detail-page convention

No stat strip (a meeting has no counters worth a row), deletion in the header menu instead of a danger zone at the foot of a page as long as the meeting, and the breadcrumb in the shell header.

## Out of scope

Taking back a dismissed name offer (needs an endpoint), the reason on the disabled *Ghi cuộc họp mới* button, notes beside the transcript, and the shell-level findings of the in-browser detector.
