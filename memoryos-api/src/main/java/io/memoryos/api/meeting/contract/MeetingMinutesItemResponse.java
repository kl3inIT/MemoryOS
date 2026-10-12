package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingMinutesItem", description = "A decision the meeting reached or work it handed out")
public record MeetingMinutesItemResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String owner,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String due,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                                                 description = "The transcript sentence the item rests on") @Nullable String quote,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID sourceUtteranceId,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean done,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "Whether these words are the owner's rather than the model's")
                                         boolean edited) {
    public static MeetingMinutesItemResponse from(Meeting.MinutesItem item) {
        return new MeetingMinutesItemResponse(item.id(), item.text(), item.owner(), item.due(), item.quote(),
                item.sourceUtteranceId(), item.done(), item.edited());
    }
}
