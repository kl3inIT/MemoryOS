# Plan

- [x] Run CI Gate on every PR and validate required, skipped and failed jobs.
- [x] Select image builds by input on PRs. Build API and worker together and run Keycloak independently.
- [x] Shard JVM and browser tests and run production-build browser checks on main and dispatch.
- [ ] Record two PR runs and a branch dispatch with job durations in the pull request.
- [ ] Keep the pull request open for review.

The [first PR run](https://github.com/kl3inIT/MemoryOS/actions/runs/45984902171) reported 261 core and 28 API JUnit suites. Their combined class inventory matches the [unsharded baseline](https://github.com/kl3inIT/MemoryOS/actions/runs/45641361015) exactly. The infrastructure job exposed assertions tied to the old workflow layout, and CI Gate failed as required. The updated deployment workflow tests exercise the new selection and release graph.

Local checks use actionlint, the Gradle wrapper to compile all backend modules, the deployment workflow test suite, and Buildx Bake's resolved plans with and without a release tag. The pull request body holds the timing comparison and job durations.
