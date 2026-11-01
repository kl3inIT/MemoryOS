package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record IntermediateReportCitationsEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                               @Schema(requiredMode = REQUIRED) long sequence,
                                               @Schema(requiredMode = REQUIRED) String toolCallId,
                                               @Schema(requiredMode = REQUIRED) List<ResearchCitation> citations) {}
