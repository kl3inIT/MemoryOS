package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A tool call waiting for the person's approval, then the same call with its decision (MEM-253). */
@Schema(name = "ToolApprovalEvent")
public record ToolApprovalEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                @Schema(requiredMode = REQUIRED) long sequence,
                                @Schema(requiredMode = REQUIRED) String toolCallId,
                                @Schema(requiredMode = REQUIRED) String serverName,
                                @Schema(requiredMode = REQUIRED) String toolName,
                                @Schema(requiredMode = REQUIRED, description = "The call's JSON arguments, cut at 4,000 characters")
                                String arguments,
                                @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, allowableValues = {"APPROVED", "DECLINED", "NO_DECISION"},
                                        description = "Absent while the call waits")
                                @Nullable String decision) {}
