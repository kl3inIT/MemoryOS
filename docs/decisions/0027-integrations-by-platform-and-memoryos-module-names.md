# 27. Integrations are Gradle modules by platform, and every module is named `memoryos-*`

Date: 2026-10-12

## Status

Accepted; implemented 2026-10-12
([MEM-247](../increments/completed/mem-247-module-layout/design.md)). Supersedes
[ADR 0016](0016-integration-bundle-named-sources.md): its single `sources` bundle and its rule that a bundle
package follows the capability it serves. [ADR 0015](0015-capability-module-map.md) stands for `core`: a package
there still belongs to the capability that changes it.

## Context

ADR 0016 kept every integration in one Gradle module, `sources`, with packages named for the capability an
adapter implements (`connector.adapter.<provider>`, `ingestion.extraction`), and allowed a provider its own module
only under measured SDK, image or rollout pressure.

A Lark bot is coming beside the Lark connector. It reads its token, sends its requests and counts them as the
connector does, so an SDK upgrade or a change of scope touches both, while the Lark and Google Drive connectors
share one small class. The thing that changes together is the platform. Laid out by capability, the code two
capabilities share for one platform has no home but a third package beside them, and the same will hold for
Microsoft (SharePoint, Teams) and Google (Drive, Chat). `sources` also names one kind of thing, although it holds
content extraction and would hold chat channels.

## Decision

- An integration is a Gradle module for one platform: `memoryos-integration-lark`, `memoryos-integration-google`,
  `memoryos-integration-microsoft`, under `io.memoryos.integration.<platform>`. It holds everything that calls
  that platform, in packages by what they serve; those packages do not import each other, and share only the
  platform's own client code. Content extraction is `memoryos-extraction` and keeps `io.memoryos.ingestion.extraction`.
- Capabilities keep their ports in `core`. `connector` and `channel` are separate modules of Spring Modulith, and
  an integration depends on public `core` APIs only, as before.
- Every Gradle module is named `memoryos-<name>` in its directory, its project path and its jar:
  `memoryos-core`, `memoryos-api`, `memoryos-worker`. Prose keeps the short names `core`, `api` and `worker`.
- Shared build configuration is a convention plugin in `build-logic` (`memoryos.java-conventions`); a module's
  build file states only what is its own, with a reason beside each dependency.
- A check at dependency resolution fails the build when a platform SDK reaches `memoryos-core`'s runtime
  classpath or another platform's, since nothing fails to compile when a transitive dependency brings one in.

## Consequences

- A platform that serves one capability is still a module of its own; the cost is a short build file.
- `SourceContentExtractorRouter` no longer names a platform's snapshot readers: each platform registers its
  readers for the input formats it writes, and the router chooses by format.
- `deploy.sh` finds applied migrations under `memoryos-core/src/main/resources/db/migration` in the release
  bundle that CI archives from the same path; the two change together.
- Not adopted from the reference that prompted the layout (spring-agent, a library published module by module):
  images built by Gradle, native images, retried tests, and a module for each capability of a platform.
- Documents of completed increments name the paths of their time and are not rewritten.
