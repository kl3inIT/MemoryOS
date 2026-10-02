# MEM-214 Chat: a lighter answer bar, labelled icon actions, the person's name, a phone header that keeps the title

Third fix batch of the 2026-10-02 UI critique; frontend only. Owner decisions 2026-10-03: skip N-1 (source chips on the first screen) and M-3 (dark composer fill).

## Requirement

1. **P2-5** Each answer carries seven controls: copy, Sources, thumbs up, thumbs down, regenerate, an unlabelled chevron for *Tạo lại bằng mô hình khác* and a git-branch icon for *Tách sang hội thoại mới*. On a phone the branch icon wraps onto its own line.
2. **A-2** Icon-only actions name themselves only through a native `title`, which never shows on keyboard focus.
3. **N-2** The temporary chat toggle is an eye-off icon with no visible label.
4. **N-3** The account button shows the role (*Chủ sở hữu*) instead of the person.
5. **M-5** On a phone, four 44 px header actions (search, files, share, more) truncate the conversation title.

## Design

- **`IconButton` names itself on hover and focus.** A `tooltip` prop shows the button's `aria-label` in the registry `Tooltip` under its own `TooltipProvider` (300 ms), as `SourceHint` does, so it works in the shell header too; the visible hint and the accessible name are the same text. The Chat actions use it instead of `title`: copy, thumbs, regenerate, edit question, branch, search in the conversation, files and the conversation menu.
- **A lighter answer bar.** *Regenerate with another model* and *Branch into a new chat* move into one "…" menu after regenerate (`ChatAnswerMenu`, assistant-ui `ActionBarMorePrimitive` like the menu it replaces). The bar reads copy, (read aloud), Sources, thumbs up, thumbs down, regenerate, "…". The branch mutation becomes `useChatBranch`, shared with the question's branch icon; a branch in progress shows on the "…" trigger and its failure under the bar.
- **Temporary chat says what it is.** The toggle shows *Chat tạm thời* beside its icon at every width (it is the new chat header's only action), with one accessible name and `aria-pressed` for its state.
- **The account button shows the person.** It shows the display name and falls back to the role when the identity provider sent none; the menu keeps name and role.
- **A phone header keeps the title.** Below `md` the conversation header shows search and "…" only: Share already sits in "…", and *Tệp trong hội thoại* joins it there, opening the same sheet.
- **Focus returns to the menu.** `FormDialog` takes a `restoreFocusRef`, as `ConfirmDialog` does, so Share opened from the "…" menu (the only way on a phone) returns focus to that menu's trigger on close.

## Reuse

Registry `Tooltip`/`TooltipProvider`; assistant-ui `ActionBarMorePrimitive`; `useIsMobile`; the existing `SharingDialog`, `ChatSessionFiles` sheet and `ChatSessionMenu`.

## Out of scope

The minor findings (batch 4) and "Tenant" in UI copy.
