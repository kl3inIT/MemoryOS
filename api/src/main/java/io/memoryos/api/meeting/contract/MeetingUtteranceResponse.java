package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingUtterance")
public record MeetingUtteranceResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String speaker,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long startMs,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long endMs,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                       List<MeetingUtteranceSpanResponse> spans,
                                       @Schema(description = "Who last changed what this line says; absent while nobody has")
                                       Meeting.@Nullable EditSource editSource) {
    public static MeetingUtteranceResponse from(Meeting.Utterance utterance) {
        return new MeetingUtteranceResponse(utterance.id(), utterance.track(), utterance.speaker(),
                utterance.startMs(), utterance.endMs(), utterance.text(), utterance.confidence(),
                utterance.spans().stream().map(MeetingUtteranceSpanResponse::from).toList(), utterance.editSource());
    }
}
