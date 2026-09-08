package io.memoryos.api.search.contract;

import io.memoryos.retrieval.SearchDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "SearchDocument")
public record SearchDocumentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID documentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID generation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Passage> passages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int firstOrdinal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalChunks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
    public static SearchDocumentResponse from(SearchDocument document) {
        return new SearchDocumentResponse(document.documentId(), document.generation(), document.title(),
                document.passages().stream().map(value -> new Passage(value.ordinal(), value.content(), value.provenanceJson())).toList(),
                document.firstOrdinal(), document.totalChunks(), document.hasMore());
    }

    public record Passage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provenanceJson) { }
}
