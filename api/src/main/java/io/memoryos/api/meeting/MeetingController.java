package io.memoryos.api.meeting;

import io.memoryos.api.chat.VoiceTicketStore;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingException;
import io.memoryos.meeting.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/meetings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Meetings")
@ApiResponse(responseCode = "400", description = "Invalid meeting request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Chat access, Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class MeetingController {
    private final MeetingService meetings;
    private final VoiceTicketStore tickets;

    MeetingController(MeetingService meetings, VoiceTicketStore tickets) {
        this.meetings = meetings;
        this.tickets = tickets;
    }

    @Schema(name = "MeetingCreateRequest", description = "What the owner enters before recording")
    record CreateRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Kind kind,
                         @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, allowableValues = {"vi", "en"})
                         @Nullable String language,
                         @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, description = "Participant names, at most 50")
                         @Nullable List<String> participants,
                         @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                                 description = "Names and terms the speech provider should prefer, at most 100")
                         @Nullable List<String> terms) {}

    @Schema(name = "MeetingNotesRequest")
    record NotesRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50000) String notes,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {}

    @Schema(name = "MeetingItemRequest")
    record ItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean done) {}

    @Schema(name = "MeetingMinutesItem", description = "A decision the meeting reached or work it handed out")
    record MinutesItemResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String owner,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String due,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                                       description = "The transcript sentence the item rests on") @Nullable String quote,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID sourceUtteranceId,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean done) {
        static MinutesItemResponse from(Meeting.MinutesItem item) {
            return new MinutesItemResponse(item.id(), item.text(), item.owner(), item.due(), item.quote(),
                    item.sourceUtteranceId(), item.done());
        }
    }

    @Schema(name = "MeetingMinutes", description = "What the model made of the meeting once it ended")
    record MinutesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.MinutesStatus status,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What kind of meeting this was")
                           String kind,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant generatedAt,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MinutesItemResponse> decisions,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MinutesItemResponse> actions) {
        static MinutesResponse from(Meeting.Minutes minutes) {
            return new MinutesResponse(minutes.status(), minutes.failure(), minutes.summary(), minutes.kind(),
                    minutes.generatedAt(), minutes.decisions().stream().map(MinutesItemResponse::from).toList(),
                    minutes.actions().stream().map(MinutesItemResponse::from).toList());
        }
    }

    @Schema(name = "MeetingSpeakerRequest", description = "A blank or absent name restores the automatic label")
    record SpeakerRequest(@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 200)
                          @Nullable String name) {}

    @Schema(name = "MeetingTicketRequest")
    record TicketRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track) {}

    @Schema(name = "MeetingTicket", description = "A 60-second single-use ticket for one meeting track's WebSocket")
    record TicketResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ticket,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {}

    @Schema(name = "MeetingSummary")
    record SummaryResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Kind kind,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Status status,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int participants,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "End of the last utterance")
                           long durationMs,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant endedAt) {
        static SummaryResponse from(Meeting.Summary summary) {
            return new SummaryResponse(summary.id(), summary.title(), summary.kind(), summary.status(), summary.participants(),
                    summary.durationMs(), summary.createdAt(), summary.endedAt());
        }
    }

    @Schema(name = "MeetingSpeaker")
    record SpeakerResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String name) {}

    @Schema(name = "MeetingUtterance")
    record UtteranceResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String speaker,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long startMs,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long endMs,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence) {
        static UtteranceResponse from(Meeting.Utterance utterance) {
            return new UtteranceResponse(utterance.id(), utterance.track(), utterance.speaker(), utterance.startMs(),
                    utterance.endMs(), utterance.text(), utterance.confidence());
        }
    }

    @Schema(name = "MeetingDetail")
    record DetailResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
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
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SpeakerResponse> speakers,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UtteranceResponse> utterances,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MinutesResponse minutes) {
        static DetailResponse from(Meeting.Detail detail) {
            return new DetailResponse(detail.id(), detail.title(), detail.kind(), detail.language(), detail.participants(),
                    detail.terms(), detail.notes(), detail.status(), detail.provider(), detail.diarized(), detail.createdAt(),
                    detail.endedAt(), detail.revision(),
                    detail.speakers().stream().map(s -> new SpeakerResponse(s.track(), s.label(), s.name())).toList(),
                    detail.utterances().stream().map(UtteranceResponse::from).toList(),
                    MinutesResponse.from(detail.minutes()));
        }
    }

    @GetMapping
    @Operation(operationId = "listMeetings", summary = "List the current member's meetings, newest first")
    @ApiResponse(responseCode = "200", description = "Meetings", useReturnTypeSchema = true)
    List<SummaryResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return meetings.list(identity.actorId()).stream().map(SummaryResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createMeeting", summary = "Create a meeting to record")
    @ApiResponse(responseCode = "201", description = "The new meeting", useReturnTypeSchema = true)
    DetailResponse create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                          @RequestBody CreateRequest body) {
        return DetailResponse.from(meetings.create(identity.actorId(), new Meeting.Draft(body.title(), body.kind(),
                body.language(), body.participants(), body.terms())));
    }

    @GetMapping("/{meetingId}")
    @Operation(operationId = "getMeeting", summary = "Read one of the current member's meetings with its transcript")
    @ApiResponse(responseCode = "200", description = "The meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                       @PathVariable UUID meetingId) {
        return DetailResponse.from(meetings.get(identity.actorId(), meetingId));
    }

    @PutMapping(value = "/{meetingId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateMeetingNotes", summary = "Replace the owner's private notes")
    @ApiResponse(responseCode = "200", description = "The meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The meeting changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse notes(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                         @PathVariable UUID meetingId, @RequestBody NotesRequest body) {
        return DetailResponse.from(meetings.updateNotes(identity.actorId(), meetingId, body.notes(), body.revision()));
    }

    @PutMapping(value = "/{meetingId}/speakers/{track}/{label}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "nameMeetingSpeaker", summary = "Name a speaker of a meeting track")
    @ApiResponse(responseCode = "200", description = "The meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or speaker not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse speaker(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                           @PathVariable UUID meetingId, @PathVariable Meeting.Track track, @PathVariable String label,
                           @RequestBody SpeakerRequest body) {
        return DetailResponse.from(meetings.nameSpeaker(identity.actorId(), meetingId, track, label, body.name()));
    }

    @PostMapping("/{meetingId}/end")
    @Operation(operationId = "endMeeting", summary = "End recording")
    @ApiResponse(responseCode = "200", description = "The ended meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse end(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                       @PathVariable UUID meetingId) {
        return DetailResponse.from(meetings.end(identity.actorId(), meetingId));
    }

    @PostMapping("/{meetingId}/minutes")
    @Operation(operationId = "rerunMeetingMinutes", summary = "Write the minutes again after the meeting changed")
    @ApiResponse(responseCode = "200", description = "The meeting, with its minutes queued", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse minutes(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                           @PathVariable UUID meetingId) {
        return DetailResponse.from(meetings.rerunMinutes(identity.actorId(), meetingId));
    }

    @PutMapping(value = "/{meetingId}/minutes/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "markMeetingMinutesItem", summary = "Tick off a task the minutes found")
    @ApiResponse(responseCode = "200", description = "The meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or item not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse item(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @PathVariable UUID meetingId, @PathVariable UUID itemId, @RequestBody ItemRequest body) {
        return DetailResponse.from(meetings.markItem(identity.actorId(), meetingId, itemId, body.done()));
    }

    @DeleteMapping("/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteMeeting", summary = "Delete a meeting and its transcript")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID meetingId) {
        meetings.delete(identity.actorId(), meetingId);
    }

    @PostMapping(value = "/{meetingId}/tickets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createMeetingTicket",
            summary = "Issue a 60-second single-use ticket for one track's recording WebSocket")
    @ApiResponse(responseCode = "200", description = "Ticket", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The meeting has ended", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    TicketResponse ticket(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                          @PathVariable UUID meetingId, @RequestBody TicketRequest body) {
        if (body.track() == null) throw MeetingException.invalid("A track is required.");
        meetings.requireRecordable(identity.actorId(), meetingId, body.track());
        var issued = tickets.issue(identity.actorId(), MeetingStreamWebSocketHandler.scope(meetingId, body.track()));
        return new TicketResponse(issued.value(), issued.expiresAt());
    }
}
