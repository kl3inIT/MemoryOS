package io.memoryos.api.chat.contract;

import io.memoryos.chat.ImageMode;
import io.memoryos.chat.WebSearchMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "Regenerate")
public record ChatRegenerateTurnRequest(@NotNull UUID clientRequestId, @Nullable UUID modelConfigurationId, @Nullable WebSearchMode webSearch, @Nullable ImageMode image,
                                        @Schema(description = "Run Deep research; absent means false. Part of request identity.") @Nullable Boolean deepResearch,
            @Size(max = 8) @Nullable List<@NotNull UUID> mcpServerIds) {}
