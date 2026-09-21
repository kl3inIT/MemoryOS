# Plan

- [x] Replace immediate Group-member writes with a local membership/manager draft and page-triggered commit.
- [x] Route Group Source-association commits through the page-level Save Changes action.
- [x] Aggregate section dirty/pending state with existing name and capability drafts; make Cancel reset all drafts.
- [x] Extend component and browser tests to prove no Group write occurs before Save Changes and that Cancel does not persist drafts.
- [x] Update the Identity contract and verification matrix with the deferred-save behavior.

## Verification — 2026-09-21

- `node node_modules/typescript/bin/tsc -b`: passed.
- `node node_modules/vitest/vitest.mjs run src/features/groups/group-members-section.test.tsx src/features/sources/source-groups-section.test.tsx`: passed — 10 tests. The member test proves staged add/cancel/save behavior; the Source tests prove an association removal creates no request before Save Changes.
- The focused Playwright test could not launch because the local Playwright Chromium executable is missing. The test remains updated and typechecked; browser-runtime evidence is pending browser installation.
