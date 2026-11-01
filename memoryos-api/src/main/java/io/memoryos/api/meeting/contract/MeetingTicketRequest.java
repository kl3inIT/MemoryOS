package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingTicketRequest")
public record MeetingTicketRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track) {}
