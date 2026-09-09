package io.memoryos.api.search.contract;

import io.memoryos.retrieval.SearchPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "SearchPage")
public record SearchPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Result> results,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int candidateLimit) {
    public static SearchPageResponse from(SearchPage page) {
        return new SearchPageResponse(page.results().stream().map(Result::from).toList(),
                page.page(), page.hasMore(), page.candidateLimit());
    }

    public record Result(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID documentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID generation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double score,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Section> sections) {
        private static Result from(SearchPage.Result result) {
            return new Result(result.documentId(), result.generation(), result.title(), result.mediaType(),
                    result.updatedAt(), result.score(), result.sections().stream().map(Section::from).toList());
        }
    }

    public record Section(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int startOrdinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int endOrdinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int matchingOrdinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double score,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChunkProvenance> provenance) {
        private static Section from(SearchPage.Section section) {
            return new Section(section.startOrdinal(), section.endOrdinal(), section.matchingOrdinal(), section.score(),
                    section.content(), section.provenance().stream()
                            .map(value -> new ChunkProvenance(value.ordinal(), value.provenanceJson())).toList());
        }
    }

    public record ChunkProvenance(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provenanceJson) { }
}
