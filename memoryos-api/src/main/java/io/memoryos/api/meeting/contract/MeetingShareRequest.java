package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "MeetingShareRequest", description = "Everyone who may read this meeting, replacing the current list")
public record MeetingShareRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> members,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> groups) {}
