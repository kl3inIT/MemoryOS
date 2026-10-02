# ADR 0021: Main reuses a pull request's verification of the same tree

## Status

Accepted 2026-10-02 by the owner. Implementation starts with the [CI main fast path](../increments/active/ci-main-fast-path/design.md) increment.

## Context

Every push to `main` ran every test job again, although the pull request it came from had just passed the same tests on its merge commit. When `main` did not move between that run and the merge, the merged commit has exactly the files the pull request run tested, including the workflow itself. The repository is owned by a user account, so GitHub's merge queue is not available.

## Decision

- A pull request run from this repository that passes `CI Gate` uploads `ci-verified-<tree>`, naming the areas it verified, kept 14 days.
- On a push to `main`, `changes` trusts such an artifact only when its run used `.github/workflows/ci.yml`, was a `pull_request` run that succeeded, came from this repository, and tested the head of a pull request the main commit came from. The tree is compared exactly.
- For each verified area, the test-only jobs (`infrastructure`, `backend`, `frontend-check`, `frontend`, `frontend-preview`) and the interpreter's test steps are skipped. Image jobs still build, smoke-test and push, because the images' revision label names the `main` commit.
- `CI Gate` accepts skipped test jobs only for an area `changes` reported as verified. Unverified areas and every area of a fork pull request run in full.
- Any failure to prove the match runs every test; the lookup never fails a main push.

## Consequences

- A main push that follows its pull request without another merge in between waits only for its image builds.
- A fork pull request's artifact is never trusted, because it could carry the name of another pull request's tree.
- Trust rests on the pull request run being the reviewed code: a pull request that weakens `ci.yml` is merged with that change, as before.
