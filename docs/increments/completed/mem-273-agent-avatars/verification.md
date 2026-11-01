# Verification

Agent faces from a stored DiceBear seed and the avatar studio ([design](design.md), [plan](plan.md)), merged in
[#695](https://github.com/kl3inIT/MemoryOS/pull/695) on 2026-10-15.

## Tests

On zm, throttled, with #695: `ChatPersistenceIntegrationTest` (49), `ChatSessionApiIntegrationTest` (3),
`OpenApiContractTest` (written, then compared), `ModulithArchitectureTest`, `CoreDependencyRulesTest`, and `pnpm check`
(191 files, 1,036 tests). CI ran the full gates. The test matrix rows are in [docs/tests/chat.md](../../../tests/chat.md)
(`AgentAvatar`, the studio and the editor page).

## Staging

2026-10-19 on release `396b72f6`, signed in as `admin@memoryos.local` through Keycloak impersonation, with one
throwaway assistant ("QA mặt 273") deleted afterwards:

- every listed assistant (10) has a face or an image;
- a new assistant gets a seed on create;
- picking another face in the studio and saving changes the seed, and the change is there after a reload;
- renaming the assistant keeps its seed;
- an image uploaded in the studio is stored, the seed is kept, and the gallery card shows the image;
- no page errors or 5xx responses; no horizontal scroll at 390 px.

Screenshots of the pinned list (home sidebar), the gallery, the editor and the studio (faces and image tabs) in light
and dark at 1440 px and 390 px were checked against the design tokens.

The faces have been in daily use through the rest of MEM-273, which the owner accepted on 2026-10-19.
