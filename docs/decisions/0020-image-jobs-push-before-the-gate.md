# ADR 0020: Image jobs push to GHCR before the gate

## Status

Accepted 2026-10-02 by the owner. Implementation starts with the [CI main fast path](../increments/active/ci-main-fast-path/design.md) increment.

## Context

Until now no image reached GHCR before `CI Gate` succeeded. Each image job saved its verified images as a tar archive and uploaded it to the Actions artifact store; after the gate, `Publish verified release` downloaded the archives (about 1.9 GB per main run: interpreter 1.07 GB, backend 816 MB, web 34 MB), loaded them and pushed them. The round trip cost about four minutes of publication on every main push, plus the upload time inside each image job.

The deployable unit was already the release artifact, not a tag: `deploy.sh` accepts only the digests `images.env` names, `deploy.yml` requires a successful `CI Gate` and `Publish verified release` in the same run, and the runbook stated that a partially published image set without a complete release is not deployable.

## Decision

- On a main push each image job (`backend-images`, `frontend-image`, `interpreter`) checks its images' revision and source labels and pushes the bytes it verified to GHCR under `sha-<source SHA>-<run>-<attempt>`, reporting each digest as a job output.
- `Publish verified release` runs after `CI Gate`, confirms each digest resolves in GHCR and writes `images.env` from those outputs. It no longer moves image bytes and needs only `packages: read`.
- Image jobs declare `packages: write`; only their main-push step uses it. A fork pull request's token stays read-only. A same-repository pull request run holds the permission but runs no push step; its author already has write access to the repository.
- A run whose gate fails leaves its images in GHCR under their run tag. They are not deployable because no `images.env` names them. No automatic cleanup is added; an operator may delete such tags.
- The landing image keeps its own artifact and `Publish landing` path.

## Consequences

- Publication takes seconds instead of about four minutes, and image jobs stop uploading archives.
- GHCR holds unverified run tags from failed main runs. Reviewers and operators treat only digests in a release artifact as releases.
- A partial rerun of a main run reuses the image jobs' outputs from the attempt that built them; the tags they name still exist.
