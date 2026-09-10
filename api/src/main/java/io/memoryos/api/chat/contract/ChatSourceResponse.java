package io.memoryos.api.chat.contract;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.memoryos.chat.ChatSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "ChatSource")
public record ChatSourceResponse(@Schema(requiredMode = REQUIRED) int citationId,
                                 @Schema(requiredMode = REQUIRED) UUID documentId,
                                 @Schema(requiredMode = REQUIRED) UUID generation,
                                 @Schema(requiredMode = REQUIRED) String title,
                                 @Schema(requiredMode = REQUIRED) int startOrdinal,
                                 @Schema(requiredMode = REQUIRED) int endOrdinal,
                                 @Schema(requiredMode = REQUIRED) List<Provenance> provenance) {
    public record Provenance(@Schema(requiredMode = REQUIRED) int ordinal,
                             @Schema(requiredMode = REQUIRED) String provenanceJson) {}
    public static ChatSourceResponse from(ChatSource source) {
        return new ChatSourceResponse(source.citationId(), source.documentId(), source.generation(), source.title(),
                source.startOrdinal(), source.endOrdinal(), source.provenance().stream()
                .map(p -> new Provenance(p.ordinal(), p.provenanceJson())).toList());
    }
}
