package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceDetail;

import java.util.List;

public record SourceDetailResponse(SourceSummaryResponse source, List<SourceItemResponse> items) {
    public SourceDetailResponse {
        items = List.copyOf(items);
    }

    public static SourceDetailResponse from(SourceDetail detail) {
        return new SourceDetailResponse(
                SourceSummaryResponse.from(detail.source()),
                detail.items().stream().map(SourceItemResponse::from).toList()
        );
    }
}
