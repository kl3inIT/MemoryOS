# Plan

1. Extend the group projection and service authorization from manager-only to direct membership while preserving active-tenant and global-read checks.
2. Permit a direct group member to retrieve only that group's associated source summaries; retain all source management and detail restrictions.
3. Surface Groups to active users and make the existing detail surface strictly read-only without management authority.
4. Add focused backend/frontend coverage, then run the affected Gradle and web checks.

## Verification

- `./gradlew.bat :core:test --tests "*PostgresIamAuthorizationTest" --tests "*PostgresGroupMembershipReplacementTest" --tests "*PostgresSourceLifecycleTest" --no-daemon` passed.
- `./gradlew.bat :sources:test --tests "*LargeChatSpreadsheetExtractionTest" --no-daemon` passed.
- `corepack pnpm --config.verify-deps-before-run=false --dir web exec tsc -b` passed.
- Targeted Vitest group/session coverage and changed-file oxlint passed.
- Repository-wide `clean check` was attempted. It found the now-updated group projection assertion and two unrelated integration failures: an image-heavy source extraction and search rebuild cancellation. The extraction test passed when rerun alone; the search test could not initialize Testcontainers despite Docker being reachable.
