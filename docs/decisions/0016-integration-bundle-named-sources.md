# 16. The integration bundle is the `sources` Gradle module, and its packages follow their capability owners

Date: 2026-09-25

## Status

Accepted; implemented 2026-09-25. Replaces the module name and the `io.memoryos.provider.<provider>` package layout of [ADR 0006](0006-shared-connector-bundle-and-jdbc-source-persistence.md); ADR 0006's single-bundle rationale and its JDBC persistence decision stand.

## Context

ADR 0006 put every provider implementation in one `:connector` Gradle integration bundle under `io.memoryos.provider.<provider>`. The bundle now holds two different things: the Google Drive and SharePoint adapters that implement the `connector` capability's provider SPI (`GoogleDriveProvider`, `SharePointProvider`, `GoogleDriveLinkReader`), and content extraction (Tika, Docling, PaddleOCR-VL, the spreadsheet readers, the extractor router) that implements the `ingestion` capability's `SourceContentExtractor` and `ChatFileExtractor`.

Two names no longer fit:

- `provider` now also means an LLM provider in `ai` (the model catalog and its provider adapters), so `io.memoryos.provider` reads as model code.
- The Gradle module `connector` has the same name as the core `connector` capability, although half of the bundle serves `ingestion`.

[ADR 0015](0015-capability-module-map.md) makes a package's owner the capability that changes it. The bundle's packages should say which capability each adapter serves.

## Decision

- The Gradle module `connector/` is renamed `sources/` (`:sources`); `api` depends on it for compilation and `worker` at runtime, as before.
- `io.memoryos.provider.google` moves to `io.memoryos.connector.adapter.googledrive` and `io.memoryos.provider.sharepoint` to `io.memoryos.connector.adapter.sharepoint`.
- `io.memoryos.provider.file` and the root `SourceContentExtractorRouter`, `SourceContentExtractorAutoConfiguration` and `StructuredContent` move to one flat `io.memoryos.ingestion.extraction` package. It stays flat because the Docling, PaddleOCR-VL and Tika classes share package-private types; splitting it would widen their visibility.
- `connector.adapter` and `ingestion.extraction` exist only in the bundle, so no package is split between `core` and `sources`.
- ADR 0006's rationale is kept: one bundle isolates provider SDKs and parsers from `core`, and a provider folder moves into its own module only under measured SDK, image or rollout pressure.

Configuration property prefixes and `@Bean` method names are unchanged. The auto-configuration classes keep their names; only their fully qualified names, listed in `AutoConfiguration.imports` and in the API's `excludeName`, change.

## Consequences

- The owner chose `sources` although "Source" is also the domain term for a connected Google Drive, SharePoint or upload collection. The Gradle module name describes the jar; the packages carry ownership.
- `StructuredContent`, `ExtractionException` and `ExtractionFailure` belong to `document`, which owns the `memoryos-extraction-v2` model they write (moved 2026-09-25, phase 3; `StructuredContent` takes the source descriptor as a plain object so `document` does not depend on `connector`). The adapters therefore depend on `connector` and `document` only, which `core`'s module map allows (`connector` → `document`); `SourcesDependencyRulesTest` enforces that `connector.adapter` does not depend on `ingestion`, and still forbids the bundle from any `application` or `persistence` package. The extractor router in `ingestion.extraction` constructs the Google Docs, Google Sheets and SharePoint page extractors, which is `ingestion` → `connector.adapter`, the allowed direction.
- The Worker already component-scans `io.memoryos.connector` and `io.memoryos.ingestion`; the bundle's configuration classes are auto-configurations, which component scanning excludes, so the scan registers nothing new.
- CI's changed-area filter, the Docker build and the deployment configuration test name `sources/`.
