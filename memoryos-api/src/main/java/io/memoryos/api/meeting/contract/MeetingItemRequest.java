package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingItemRequest")
public record MeetingItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean done) {}
