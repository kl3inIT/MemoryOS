package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryEntryDocument", description = "Where a Source document comes from and what Search serves of it")
public record ChatLibraryEntryDocumentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                description = "The generation Search serves, to preview it; null while it is being indexed")
        @Nullable UUID generation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"FILE", "GOOGLE_DRIVE", "SHAREPOINT"})
        String sourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "The document in its provider, when it has one") @Nullable String providerUrl
) {

    public static ChatLibraryEntryDocumentResponse from(LibraryEntry.DocumentLink document) {
        return new ChatLibraryEntryDocumentResponse(document.generation(), document.title(), document.sourceId(),
                document.sourceName(), document.sourceType().name(), document.providerUrl());
    }
}
