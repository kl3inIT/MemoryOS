package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingLibraryFile", description = "The minutes as a file in the caller's library")
public record MeetingLibraryFileResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID fileId,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "READY when Chat can read it; PROCESSING while it is extracted")
                                         String status) {}
