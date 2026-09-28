# Plan — search index content errors

1. `core/document`: add `DocumentContentException(code)`; chunker merges
   adjacent text blocks and throws typed codes.
2. `core/document`: `DocumentChunkPort.markSearchFailed` accepts the error
   code; `DocumentChunkService` passes it through.
3. `core/ingestion`: `SearchIngestionCoordinator` fails
   `DocumentContentException` immediately with its code.
4. `core/connector` + `api`: project `documents.search_error_code` onto
   `SourceItemView.searchErrorCode` and `SourceItemResponse`.
5. `web`: regenerate hey-api types, render the specific message under the
   filename, add English/Vietnamese strings.
6. Tests: chunker merge + limit codes; coordinator fail-fast; item projection.
