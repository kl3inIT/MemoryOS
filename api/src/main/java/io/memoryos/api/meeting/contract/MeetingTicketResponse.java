package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "MeetingTicket", description = "A 60-second single-use ticket for one meeting track's WebSocket")
public record MeetingTicketResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ticket,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {}
