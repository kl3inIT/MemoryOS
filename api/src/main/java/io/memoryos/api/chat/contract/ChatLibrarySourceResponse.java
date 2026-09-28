package io.memoryos.api.chat.contract;

import io.memoryos.retrieval.ShelfSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibrarySource", description = "A Source the viewer may read from, whatever its state")
public record ChatLibrarySourceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"FILE", "GOOGLE_DRIVE", "SHAREPOINT"})
        String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PUBLIC", "PRIVATE", "SYNC"},
                description = "What admits the viewer: every member, a Group of theirs, or the provider's own grants")
        String access,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"NOT_STARTED", "INDEXING", "ACTIVE", "PAUSED", "FAILED"},
                description = "A paused Source keeps its documents out of Search until it resumes")
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
                description = "Documents of this Source the viewer may read, counted whatever the Source status")
        long readableDocuments,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time",
                description = "When the last synchronization succeeded; null before the first one")
        @Nullable Instant lastSucceededAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The viewer's own Groups granted this PRIVATE Source, sorted; empty for other access")
        List<String> groups,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "The person responsible for this Source, when one is appointed")
        @Nullable String managerName
) {

    public static ChatLibrarySourceResponse from(ShelfSource source) {
        return new ChatLibrarySourceResponse(source.id(), source.name(), source.type().name(), source.access().name(),
                source.status().name(), source.readableDocuments(), source.lastSucceededAt(), source.groups(),
                source.managerName());
    }
}
