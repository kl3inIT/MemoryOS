# Plan

1. Remove direct Group membership from Group and Source-administration authorization; retain global readers and scoped ordinary-Group managers.
2. Keep Employee access to questions, searches, citations and eligible documents without Groups or Sources administration navigation.
3. Preserve manager-scoped Group members, Source associations and synchronization views; preserve global Users/Groups administration for the existing HR capability bundle and System Administrator controls.
4. Cover direct-member denial, manager-scoped navigation and Source-association visibility.

## Verification

- `./gradlew.bat :core:compileTestJava --no-daemon` passed. The focused PostgreSQL runtime tests could not create their Testcontainers database locally (their shared setup failed before test bodies); pull-request CI remains the runtime authority.
- `corepack pnpm --config.verify-deps-before-run=false --dir web exec tsc -b`, targeted Vitest (36 tests) and changed-file `oxlint --deny-warnings` passed.
- Pull-request CI under the supported Node/Docker environment is pending.
