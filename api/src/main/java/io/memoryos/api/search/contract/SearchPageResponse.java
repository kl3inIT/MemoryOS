package io.memoryos.api.search.contract;

import io.memoryos.connector.SourceType;
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Readable Documents among the bounded candidates") int totalResults,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int candidateLimit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Readable candidates before the connector filter, in total and per connector")
        SourceFacets sourceFacets) {
    public static SearchPageResponse from(SearchPage page) {
        var facets = page.sourceFacets();
        return new SearchPageResponse(page.results().stream().map(Result::from).toList(),
                page.page(), page.hasMore(), page.totalResults(), page.candidateLimit(),
                new SourceFacets(facets.total(), facets.types().stream()
                        .map(facet -> new SourceTypeFacet(facet.type(), facet.count())).toList()));
    }

    public record SourceFacets(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Candidates before the connector filter") int total,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Connectors with at least one readable candidate; a Document mapped to two connectors counts in both")
            List<SourceTypeFacet> types) { }

    public record SourceTypeFacet(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count) { }

    public record Result(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID documentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID generation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double score,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Section> sections,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceType> sourceTypes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> authors,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @org.jspecify.annotations.Nullable String providerUrl) {
        private static Result from(SearchPage.Result result) {
            return new Result(result.documentId(), result.generation(), result.title(), result.mediaType(),
                    result.updatedAt(), result.score(), result.sections().stream().map(Section::from).toList(),
                    result.sourceTypes(), result.authors(), result.providerUrl());
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
