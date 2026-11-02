package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatFollowUpsResponse")
public record ChatFollowUpsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to three questions to ask next; empty when the answer gets none")
        List<String> suggestions) {}
