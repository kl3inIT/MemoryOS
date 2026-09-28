package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingDetail")
public record MeetingDetailResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Kind kind,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String language,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> participants,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> terms,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String notes,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Status status,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String provider,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether speaker labels distinguish people")
                                    boolean diarized,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant endedAt,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingSpeakerResponse> speakers,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingUtteranceResponse> utterances,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MeetingMinutesResponse minutes,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MeetingAudioResponse audio,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                            description = "Whether the reader recorded this meeting; only its owner may edit it")
                                    boolean owned,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                            description = "Who the meeting is shared with; empty for anyone but its owner")
                                    List<MeetingReaderResponse> readers,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                            description = "Lines the caller starred; another reader's stars are their own")
                                    List<UUID> starred,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                            description = "Moments the caller marked while the meeting was running")
                                    List<MeetingBookmarkResponse> bookmarks,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                            description = "Whether a correction pass is running on this meeting right now")
                                    boolean correcting) {
    public static MeetingDetailResponse from(Meeting.Detail detail) {
        return new MeetingDetailResponse(detail.id(), detail.title(), detail.kind(), detail.language(), detail.participants(),
                detail.terms(), detail.notes(), detail.status(), detail.provider(), detail.diarized(), detail.createdAt(),
                detail.endedAt(), detail.revision(),
                detail.speakers().stream().map(MeetingSpeakerResponse::from).toList(),
                detail.utterances().stream().map(MeetingUtteranceResponse::from).toList(),
                MeetingMinutesResponse.from(detail.minutes()), MeetingAudioResponse.from(detail.audio()), detail.owned(),
                detail.readers().stream().map(MeetingReaderResponse::from).toList(), detail.starred(),
                detail.bookmarks().stream().map(MeetingBookmarkResponse::from).toList(), detail.correcting());
    }
}
