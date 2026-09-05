# Plan

- [x] Inspect current FILE flow and Onyx implementation.
- [x] Implement single-step form and recovery.
- [x] Verify validation, single-flight, retry and navigation (5 FILE browser scenarios passed).
- [x] Inspect desktop light/dark and mobile screenshots; pnpm check passed, including 44 unit tests and production build.
- [x] Update canonical contracts and prepare screenshot handoff.
- [x] User approved direct main integration and deployment; no pull request.

Screenshots: `web/test-results/file-source-setup-FILE-single-step-setup-none-chromium/file-setup-{desktop,dark,mobile}.png`. Desktop 1280x720 includes the primary action without scrolling; mobile 390x844 has no horizontal overflow. Browser API/object-storage responses were mocked; staging upload was not rerun. Backend was unchanged, so Gradle clean check was not rerun for this frontend slice.
