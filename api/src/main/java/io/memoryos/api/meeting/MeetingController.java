package io.memoryos.api.meeting;

import io.memoryos.api.chat.VoiceTicketStore;
import io.memoryos.api.meeting.contract.MeetingBookmarkResponse;
import io.memoryos.api.meeting.contract.MeetingCorrectionAppliedResponse;
import io.memoryos.api.meeting.contract.MeetingCorrectionResponse;
import io.memoryos.api.meeting.contract.MeetingMinutesItemResponse;
import io.memoryos.api.meeting.contract.MeetingMinutesSummaryResponse;
import io.memoryos.api.meeting.contract.MeetingNotesResponse;
import io.memoryos.api.meeting.contract.MeetingParticularsResponse;
import io.memoryos.api.meeting.contract.MeetingReaderResponse;
import io.memoryos.api.meeting.contract.MeetingSpeakerResponse;
import io.memoryos.api.meeting.contract.MeetingUtteranceResponse;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingCorrectionService;
import io.memoryos.meeting.MeetingException;
import io.memoryos.chat.voice.BatchTranscriptionService;
import io.memoryos.chat.voice.VoiceProvider;
import io.memoryos.chat.ChatFileInUseException;
import io.memoryos.chat.ChatFileService;
import io.memoryos.chat.ChatLibraryFile;
import io.memoryos.chat.ChatLibraryService;
import io.memoryos.meeting.MeetingMinutesDocument;
import io.memoryos.meeting.MeetingMinutesMarkdown;
import io.memoryos.meeting.MeetingRecordingService;
import io.memoryos.meeting.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MeetingController.class);
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MeetingService meetings;
    private final MeetingRecordingService recordings;
    private final ChatLibraryService library;
    private final ChatFileService files;
    private final VoiceTicketStore tickets;
    private final MeetingCorrectionService corrections;

    MeetingController(MeetingService meetings, MeetingRecordingService recordings, ChatLibraryService library,
                      ChatFileService files, VoiceTicketStore tickets, MeetingCorrectionService corrections) {
        this.meetings = meetings;
        this.recordings = recordings;
        this.library = library;
        this.files = files;
        this.tickets = tickets;
        this.corrections = corrections;
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

    @Schema(name = "MeetingLibraryFile", description = "The minutes as a file in the caller's library")
    record LibraryFileResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID fileId,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                       description = "READY when Chat can read it; PROCESSING while it is extracted")
                               String status) {}

    @Schema(name = "MeetingShareRequest", description = "Everyone who may read this meeting, replacing the current list")
    record ShareRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> members,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> groups) {}

    @Schema(name = "MeetingRecordingRequest", description = "Declared before the bytes are uploaded and checked against them afterwards")
    record RecordingRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hex SHA-256 of the file")
                            String sha256,
                            @Schema(nullable = true, description = "The speech provider to transcribe with; the Tenant's own is used when absent")
                            @Nullable VoiceProvider provider) {}

    @Schema(name = "MeetingTranscriber", description = "A speech connection a recording may be transcribed with")
    record TranscriberResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) VoiceProvider provider,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                       description = "Whether it separates the speakers of a recording") boolean diarizes,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                       description = "The largest recording this provider accepts") long maxBytes,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                       description = "Whether it is the Tenant's own choice") boolean selected) {
        static TranscriberResponse from(BatchTranscriptionService.Transcriber transcriber) {
            return new TranscriberResponse(transcriber.provider(), transcriber.model(), transcriber.diarizes(),
                    transcriber.maxBytes(), transcriber.selected());
        }
    }

    @Schema(name = "MeetingRecordingReservation", description = "Where to send the recording, and the meeting as it now reads")
    record ReservationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) DetailResponse meeting,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String method,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String uploadUrl,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> requiredHeaders,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
        static ReservationResponse from(MeetingRecordingService.Reservation reservation) {
            var upload = reservation.upload();
            // Object storage always authorizes a fresh reservation; without one there is nowhere to send the bytes.
            if (upload == null) throw MeetingException.invalid("Storage is not available for a recording.");
            return new ReservationResponse(DetailResponse.from(reservation.meeting()), upload.method(),
                    upload.uri().toString(), upload.requiredHeaders(), upload.expiresAt());
        }
    }

    @Schema(name = "MeetingAudio", description = "An uploaded recording being turned into a transcript")
    record AudioResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.AudioStatus status,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String filename,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String provider) {
        static AudioResponse from(Meeting.Audio audio) {
            return new AudioResponse(audio.status(), audio.failure(), audio.filename(), audio.sizeBytes(),
                    audio.provider());
        }
    }

    @Schema(name = "MeetingHeadingRequest",
            description = "The parts of a biên bản the transcript cannot supply; a blank field prints as an ellipsis")
    record HeadingRequest(String organization, String parentOrganization, String number, String about, String place,
                          String opened, String closed, String chair, String chairRole, String secretary,
                          String secretaryRole, List<String> attendees,
                          @Schema(description = "Times New Roman, Arial, Calibri or Tahoma; anything else is set in "
                                  + "Times New Roman, which the decree asks for") @Nullable String font) {
        MeetingMinutesDocument.Heading toHeading() {
            return new MeetingMinutesDocument.Heading(text(organization), text(parentOrganization), text(number),
                    text(about), text(place), text(opened), text(closed), text(chair), text(chairRole), text(secretary),
                    text(secretaryRole),
                    attendees == null ? List.of()
                            : attendees.stream().map(HeadingRequest::text).toList(), text(font));
        }

        private static String text(@Nullable String value) {
            return value == null ? "" : value;
        }
    }

    @Schema(name = "MeetingHeading", description = "The biên bản heading as the owner last saved it")
    record HeadingResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                   description = "Whether the owner saved one for this meeting. Otherwise only the "
                                           + "organization, its parent and the typeface are filled, carried from "
                                           + "the caller's last biên bản") boolean saved,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String organization,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String parentOrganization,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String number,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String about,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String place,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String opened,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String closed,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chair,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chairRole,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String secretary,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String secretaryRole,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> attendees,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String font) {
        static HeadingResponse from(MeetingMinutesDocument.Heading heading) {
            return from(heading, true);
        }

        static HeadingResponse from(MeetingMinutesDocument.Heading heading, boolean saved) {
            return new HeadingResponse(saved, heading.organization(), heading.parentOrganization(), heading.number(),
                    heading.about(), heading.place(), heading.opened(), heading.closed(), heading.chair(),
                    heading.chairRole(), heading.secretary(), heading.secretaryRole(), heading.attendees(),
                    heading.font());
        }

        static HeadingResponse none() {
            return new HeadingResponse(false, "", "", "", "", "", "", "", "", "", "", "", List.of(), "");
        }
    }

    @Schema(name = "MeetingUpdateRequest", description = "What the owner fills in once the meeting is under way")
    record UpdateRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Participant names, at most 50")
                         List<String> participants) {}

    @Schema(name = "MeetingNewMinutesItemRequest", description = "A decision or a piece of work the model missed")
    record NewItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DECISION", "ACTION"})
                          Meeting.ItemKind kind,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 2000) String text,
                          @Schema(nullable = true) @Size(max = 200) @Nullable String owner,
                          @Schema(nullable = true) @Size(max = 100) @Nullable String due) {}

    @Schema(name = "MeetingItemRequest")
    record ItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean done) {}

    @Schema(name = "MeetingMinutes", description = "What the model made of the meeting once it ended")
    record MinutesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.MinutesStatus status,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What kind of meeting this was")
                           String kind,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant generatedAt,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingMinutesItemResponse> decisions,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingMinutesItemResponse> actions,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                   description = "Whether the words standing now are the owner's rather than the model's")
                           boolean edited,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                   description = "The subjects the meeting moved through, each at the line it began")
                           List<MeetingMinutesItemResponse> topics) {
        static MinutesResponse from(Meeting.Minutes minutes) {
            return new MinutesResponse(minutes.status(), minutes.failure(), minutes.summary(), minutes.kind(),
                    minutes.generatedAt(), minutes.decisions().stream().map(MeetingMinutesItemResponse::from).toList(),
                    minutes.actions().stream().map(MeetingMinutesItemResponse::from).toList(), minutes.edited(),
                    minutes.topics().stream().map(MeetingMinutesItemResponse::from).toList());
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
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant endedAt,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                   description = "Whether the member recorded it, as opposed to being shared it")
                           boolean owned) {
        static SummaryResponse from(Meeting.Summary summary) {
            return new SummaryResponse(summary.id(), summary.title(), summary.kind(), summary.status(), summary.participants(),
                    summary.durationMs(), summary.createdAt(), summary.endedAt(), summary.owned());
        }
    }

    @Schema(name = "MeetingMinutesSummaryRequest")
    record SummaryRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 20000) String summary) {}

    @Schema(name = "MeetingMinutesItemRequest")
    record MinutesItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 2000) String text,
                              @Schema(nullable = true) @Size(max = 200) @Nullable String owner,
                              @Schema(nullable = true) @Size(max = 100) @Nullable String due) {}

    @Schema(name = "MeetingBookmarkRequest")
    record BookmarkRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                   description = "Milliseconds from the start of the recording") long atMs,
                           @Schema(description = "What to call it; a number is used when this is left out",
                                   nullable = true) @Size(max = 200) @Nullable String label) {}

    @Schema(name = "MeetingCorrectionRun")
    record RunResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID runId,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingCorrectionResponse> corrections) {
        static RunResponse from(MeetingCorrectionService.Run run) {
            return new RunResponse(run.id(), run.corrections().stream().map(MeetingCorrectionResponse::from).toList());
        }
    }

    @Schema(name = "AcceptMeetingCorrection")
    record AcceptCorrectionRequest(
            @Schema(description = "The caller's own wording instead of the model's", nullable = true)
            @Size(max = 2000) @Nullable String text) {}

    @Schema(name = "MeetingCorrectionRunRef")
    record RunRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID runId) {}

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
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingSpeakerResponse> speakers,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingUtteranceResponse> utterances,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MinutesResponse minutes,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AudioResponse audio,
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
        static DetailResponse from(Meeting.Detail detail) {
            return new DetailResponse(detail.id(), detail.title(), detail.kind(), detail.language(), detail.participants(),
                    detail.terms(), detail.notes(), detail.status(), detail.provider(), detail.diarized(), detail.createdAt(),
                    detail.endedAt(), detail.revision(),
                    detail.speakers().stream().map(MeetingSpeakerResponse::from).toList(),
                    detail.utterances().stream().map(MeetingUtteranceResponse::from).toList(),
                    MinutesResponse.from(detail.minutes()), AudioResponse.from(detail.audio()), detail.owned(),
                    detail.readers().stream().map(MeetingReaderResponse::from).toList(), detail.starred(),
                    detail.bookmarks().stream().map(MeetingBookmarkResponse::from).toList(), detail.correcting());
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
    @ApiResponse(responseCode = "200", description = "The notes as stored and the new revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The meeting changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingNotesResponse notes(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                               @PathVariable UUID meetingId, @RequestBody NotesRequest body) {
        return MeetingNotesResponse.from(meetings.updateNotes(identity.actorId(), meetingId, body.notes(),
                body.revision()));
    }

    @PutMapping(value = "/{meetingId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateMeeting", summary = "Rename the meeting and say who was in it")
    @ApiResponse(responseCode = "200", description = "The name and the people as stored, and the new revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingParticularsResponse update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                      @PathVariable UUID meetingId, @RequestBody UpdateRequest body) {
        return MeetingParticularsResponse.from(meetings.updateDetails(identity.actorId(), meetingId, body.title(),
                body.participants()));
    }

    @GetMapping("/{meetingId}/minutes/heading")
    @Operation(operationId = "getMeetingMinutesHeading", summary = "The biên bản heading as the owner last saved it")
    @ApiResponse(responseCode = "200", description = "The heading", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    HeadingResponse heading(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                            @PathVariable UUID meetingId) {
        return meetings.heading(identity.actorId(), meetingId).map(HeadingResponse::from)
                .or(() -> meetings.carriedHeading(identity.actorId()).map(last -> HeadingResponse.from(last, false)))
                .orElseGet(HeadingResponse::none);
    }

    @PutMapping(value = "/{meetingId}/minutes/heading", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "saveMeetingMinutesHeading", summary = "Keep the biên bản heading the owner typed")
    @ApiResponse(responseCode = "200", description = "The heading as stored", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    HeadingResponse saveHeading(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                @PathVariable UUID meetingId, @RequestBody HeadingRequest body) {
        return HeadingResponse.from(meetings.saveHeading(identity.actorId(), meetingId, body.toHeading()));
    }

    @PutMapping(value = "/{meetingId}/speakers/{track}/{label}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "nameMeetingSpeaker", summary = "Name a speaker of a meeting track")
    @ApiResponse(responseCode = "200", description = "The speaker as now named", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or speaker not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingSpeakerResponse speaker(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                   @PathVariable UUID meetingId, @PathVariable Meeting.Track track,
                                   @PathVariable String label, @RequestBody SpeakerRequest body) {
        return MeetingSpeakerResponse.from(meetings.nameSpeaker(identity.actorId(), meetingId, track, label,
                body.name()));
    }

    @DeleteMapping("/{meetingId}/speakers/{track}/{label}/suggestion")
    @Operation(operationId = "dismissMeetingSpeakerSuggestion", summary = "Keep the automatic label for a speaker")
    @ApiResponse(responseCode = "200", description = "The speaker, no longer offered a name", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or speaker not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingSpeakerResponse dismissSuggestion(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                             @PathVariable UUID meetingId, @PathVariable Meeting.Track track,
                                             @PathVariable String label) {
        return MeetingSpeakerResponse.from(meetings.dismissSpeakerSuggestion(identity.actorId(), meetingId, track,
                label));
    }

    @PostMapping("/{meetingId}/end")
    @Operation(operationId = "endMeeting", summary = "End recording")
    @ApiResponse(responseCode = "200", description = "The ended meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse end(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                       @PathVariable UUID meetingId) {
        return DetailResponse.from(meetings.end(identity.actorId(), meetingId));
    }

    @PutMapping("/{meetingId}/utterances/{utteranceId}/star")
    @Operation(operationId = "starMeetingUtterance", summary = "Mark one line as one the caller cares about")
    @ApiResponse(responseCode = "200", description = "Every line the caller starred in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<UUID> star(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @PathVariable UUID meetingId, @PathVariable UUID utteranceId) {
        return meetings.star(identity.actorId(), meetingId, utteranceId, true);
    }

    @DeleteMapping("/{meetingId}/utterances/{utteranceId}/star")
    @Operation(operationId = "unstarMeetingUtterance", summary = "Take the caller's mark off a line")
    @ApiResponse(responseCode = "200", description = "Every line the caller still has starred in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<UUID> unstar(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                      @PathVariable UUID meetingId, @PathVariable UUID utteranceId) {
        return meetings.star(identity.actorId(), meetingId, utteranceId, false);
    }

    @PostMapping(value = "/{meetingId}/bookmarks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "bookmarkMeetingMoment",
            summary = "Mark the moment the caller is at, while the meeting is still running")
    @ApiResponse(responseCode = "200", description = "Every mark the caller left in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<MeetingBookmarkResponse> bookmark(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                           @PathVariable UUID meetingId, @Valid @RequestBody BookmarkRequest body) {
        return meetings.bookmark(identity.actorId(), meetingId, body.atMs(), body.label()).stream()
                .map(MeetingBookmarkResponse::from).toList();
    }

    @DeleteMapping("/{meetingId}/bookmarks/{bookmarkId}")
    @Operation(operationId = "removeMeetingBookmark", summary = "Take back one of the caller's own marks")
    @ApiResponse(responseCode = "200", description = "Every mark the caller still has in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or mark not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<MeetingBookmarkResponse> removeBookmark(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                 @PathVariable UUID meetingId, @PathVariable UUID bookmarkId) {
        return meetings.removeBookmark(identity.actorId(), meetingId, bookmarkId).stream()
                .map(MeetingBookmarkResponse::from).toList();
    }

    @PostMapping("/{meetingId}/corrections")
    @Operation(operationId = "proposeMeetingCorrections",
            summary = "Ask a model what the speech provider probably meant at each uncertain stretch")
    @ApiResponse(responseCode = "200", description = "What the pass proposed; the transcript is unchanged",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "A pass is already running", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    RunResponse propose(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @PathVariable UUID meetingId) {
        return RunResponse.from(corrections.propose(identity.actorId(), meetingId));
    }

    @GetMapping("/{meetingId}/corrections")
    @Operation(operationId = "listMeetingCorrections",
            summary = "Every proposal made for this meeting, decided or not")
    @ApiResponse(responseCode = "200", description = "The proposals", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<MeetingCorrectionResponse> corrections(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                @PathVariable UUID meetingId) {
        return corrections.corrections(identity.actorId(), meetingId).stream().map(MeetingCorrectionResponse::from)
                .toList();
    }

    @PostMapping(value = "/{meetingId}/corrections/{correctionId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptMeetingCorrection",
            summary = "Put the proposed words, or the caller's own, into the transcript")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the accepted proposal", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The line moved since the proposal was made", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingCorrectionAppliedResponse accept(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                            @PathVariable UUID meetingId, @PathVariable UUID correctionId,
                                            @RequestBody AcceptCorrectionRequest body) {
        return MeetingCorrectionAppliedResponse.from(corrections.accept(identity.actorId(), meetingId, correctionId,
                body.text()));
    }

    @Schema(name = "MeetingWordCorrectionRequest", description = "What was said at one marked stretch of a line")
    record WordCorrectionRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int start,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int end,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000) String text) {}

    @PostMapping(value = "/{meetingId}/utterances/{utteranceId}/corrections",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "correctMeetingWords",
            summary = "Write what was said at a stretch the provider was unsure of")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the correction recorded for it", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "Still recording, or the stretch is no longer marked", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingCorrectionAppliedResponse correctWords(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                  @PathVariable UUID meetingId, @PathVariable UUID utteranceId,
                                                  @RequestBody WordCorrectionRequest body) {
        return MeetingCorrectionAppliedResponse.from(corrections.correctByHand(identity.actorId(), meetingId,
                utteranceId, body.start(), body.end(), body.text() == null ? "" : body.text()));
    }

    @PostMapping("/{meetingId}/corrections/{correctionId}/keep")
    @Operation(operationId = "keepMeetingWording", summary = "Decline a proposal and keep what the provider heard")
    @ApiResponse(responseCode = "200", description = "The declined proposal; no line changed", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingCorrectionResponse keep(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                   @PathVariable UUID meetingId, @PathVariable UUID correctionId) {
        return MeetingCorrectionResponse.from(corrections.keep(identity.actorId(), meetingId, correctionId));
    }

    @PostMapping(value = "/{meetingId}/corrections/accept-all", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptAllMeetingCorrections", summary = "Accept everything a pass proposed and has not decided")
    @ApiResponse(responseCode = "200", description = "The meeting with every accepted line rewritten", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse acceptAll(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                             @PathVariable UUID meetingId, @RequestBody RunRequest body) {
        return DetailResponse.from(corrections.acceptAll(identity.actorId(), meetingId, body.runId()));
    }

    @PostMapping(value = "/{meetingId}/corrections/revert-all", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revertAllMeetingCorrections", summary = "Take back everything one pass put in")
    @ApiResponse(responseCode = "200", description = "The meeting with every line of that pass restored", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "A line changed again after the pass", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse revertAll(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                             @PathVariable UUID meetingId, @RequestBody RunRequest body) {
        return DetailResponse.from(corrections.revertAll(identity.actorId(), meetingId, body.runId()));
    }

    @PostMapping("/{meetingId}/corrections/{correctionId}/revert")
    @Operation(operationId = "revertMeetingCorrection", summary = "Put back what the line said before this proposal")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the reverted proposal", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The line changed again after this proposal", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingCorrectionAppliedResponse revert(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                            @PathVariable UUID meetingId, @PathVariable UUID correctionId) {
        return MeetingCorrectionAppliedResponse.from(corrections.revert(identity.actorId(), meetingId, correctionId));
    }

    @PostMapping("/{meetingId}/minutes")
    @Operation(operationId = "rerunMeetingMinutes", summary = "Write the minutes again after the meeting changed")
    @ApiResponse(responseCode = "200", description = "The meeting, with its minutes queued", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "The minutes were corrected by hand; say so to discard that work", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse minutes(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                           @PathVariable UUID meetingId,
                           @Parameter(description = "Required once the minutes were corrected by hand, because a "
                                   + "rerun writes them again and throws that work away")
                           @RequestParam(defaultValue = "false") boolean discardEdits) {
        var meeting = DetailResponse.from(meetings.rerunMinutes(identity.actorId(), meetingId, discardEdits));
        // The published minutes are about to be wrong. Drop them so the next use publishes what was rewritten; a
        // file a Project or an Agent still holds is left alone rather than pulled out from under them.
        library.published(identity.actorId(), ChatLibraryFile.Source.MEETING, meetingId).ifPresent(file -> {
            try {
                files.delete(identity.actorId(), file.id());
            } catch (ChatFileInUseException inUse) {
                LOG.info("Published meeting minutes kept because something still uses them");
            }
        });
        return meeting;
    }

    @PutMapping(value = "/{meetingId}/minutes/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "markMeetingMinutesItem", summary = "Tick off a task the minutes found")
    @ApiResponse(responseCode = "200", description = "The item as it now reads", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or item not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingMinutesItemResponse item(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                    @PathVariable UUID meetingId, @PathVariable UUID itemId,
                                    @RequestBody ItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.markItem(identity.actorId(), meetingId, itemId, body.done()));
    }

    @PutMapping(value = "/{meetingId}/shares", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "shareMeeting", summary = "Say who else may read this meeting")
    @ApiResponse(responseCode = "200", description = "Everyone the meeting is now shared with", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    List<MeetingReaderResponse> share(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                      @PathVariable UUID meetingId, @RequestBody ShareRequest body) {
        return meetings.share(identity.actorId(), meetingId, body.members(), body.groups()).stream()
                .map(MeetingReaderResponse::from).toList();
    }

    @PostMapping("/{meetingId}/library")
    @Operation(operationId = "publishMeetingMinutes",
            summary = "Take the minutes into the caller's file library so a conversation can use them")
    @ApiResponse(responseCode = "200", description = "The file", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    LibraryFileResponse publish(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                @PathVariable UUID meetingId) {
        var meeting = meetings.get(identity.actorId(), meetingId);
        if (meeting.minutes().status() != Meeting.MinutesStatus.READY)
            throw MeetingException.invalid("The minutes are not written yet.");
        var file = library.publish(identity.actorId(), ChatLibraryFile.Source.MEETING, meetingId,
                MeetingMinutesMarkdown.filename(meeting), "text/markdown", MeetingMinutesMarkdown.render(meeting));
        return new LibraryFileResponse(file.id(), file.filename(), file.status().name());
    }

    @GetMapping("/transcribers")
    @Operation(operationId = "listMeetingTranscribers",
            summary = "The speech connections a recording may be transcribed with, the Tenant's own first")
    @ApiResponse(responseCode = "200", description = "Transcribers", useReturnTypeSchema = true)
    List<TranscriberResponse> transcribers(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return recordings.transcribers(identity.actorId()).stream().map(TranscriberResponse::from).toList();
    }

    @PostMapping(value = "/{meetingId}/recording", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reserveMeetingRecording", summary = "Reserve storage for a recording of this meeting")
    @ApiResponse(responseCode = "200", description = "Where to send the recording", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "409", description = "The meeting is no longer recording", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    ReservationResponse reserve(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                @PathVariable UUID meetingId, @RequestBody RecordingRequest body) {
        return ReservationResponse.from(recordings.reserve(identity.actorId(), meetingId,
                new MeetingRecordingService.Upload(body.filename(), body.mediaType(), body.sizeBytes(), body.sha256(),
                        body.provider())));
    }

    @PostMapping("/{meetingId}/recording/finalize")
    @Operation(operationId = "finalizeMeetingRecording", summary = "Accept the uploaded recording and queue its transcript")
    @ApiResponse(responseCode = "200", description = "The meeting, with its recording queued", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    DetailResponse finalizeRecording(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                     @PathVariable UUID meetingId) {
        return DetailResponse.from(recordings.finalizeUpload(identity.actorId(), meetingId));
    }

    @PostMapping(value = "/{meetingId}/minutes/export", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {DOCX, MediaType.APPLICATION_PDF_VALUE})
    @Operation(operationId = "exportMeetingMinutes", summary = "Download the minutes as a Vietnamese biên bản, in Word or as a PDF")
    @ApiResponse(responseCode = "200", description = "The biên bản",
            content = {@Content(mediaType = DOCX, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    ResponseEntity<byte[]> export(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                  @PathVariable UUID meetingId, @RequestBody HeadingRequest body,
                                  @RequestParam(defaultValue = "DOCX") MeetingService.TranscriptFormat format) {
        byte[] document = meetings.exportMinutes(identity.actorId(), meetingId, body.toHeading(), format);
        boolean pdf = format == MeetingService.TranscriptFormat.PDF;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("bien-ban-" + meetingId + (pdf ? ".pdf" : ".docx"), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType(DOCX)).body(document);
    }

    @PutMapping(value = "/{meetingId}/minutes/summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editMeetingMinutesSummary", summary = "Rewrite the summary in the owner's own words")
    @ApiResponse(responseCode = "200", description = "The summary as it now reads", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "The minutes are not written yet", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingMinutesSummaryResponse editSummary(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                              @PathVariable UUID meetingId, @Valid @RequestBody SummaryRequest body) {
        return MeetingMinutesSummaryResponse.from(meetings.editSummary(identity.actorId(), meetingId, body.summary()));
    }

    @PutMapping(value = "/{meetingId}/minutes/items/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editMeetingMinutesItem",
            summary = "Rewrite one decision or one piece of work, its owner and its deadline")
    @ApiResponse(responseCode = "200", description = "The item as it now reads", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "A decision has no owner and no deadline", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "404", description = "Meeting or item not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingMinutesItemResponse editItem(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                        @PathVariable UUID meetingId, @PathVariable UUID itemId,
                                        @Valid @RequestBody MinutesItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.editItem(identity.actorId(), meetingId, itemId, body.text(),
                body.owner(), body.due()));
    }

    @PostMapping(value = "/{meetingId}/minutes/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addMeetingMinutesItem", summary = "Write in a decision or a piece of work the model missed")
    @ApiResponse(responseCode = "200", description = "The item written in, placed after the others of its kind", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    MeetingMinutesItemResponse addItem(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                       @PathVariable UUID meetingId, @Valid @RequestBody NewItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.addItem(identity.actorId(), meetingId, body.kind(),
                body.text(), body.owner(), body.due()));
    }

    @DeleteMapping("/{meetingId}/minutes/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeMeetingMinutesItem", summary = "Take a decision or a piece of work out of the minutes")
    @ApiResponse(responseCode = "204", description = "Removed; the minutes now count as the owner's words")
    @ApiResponse(responseCode = "404", description = "Meeting or item not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    void removeItem(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @PathVariable UUID meetingId, @PathVariable UUID itemId) {
        meetings.removeItem(identity.actorId(), meetingId, itemId);
    }

    @GetMapping("/{meetingId}/transcript")
    @Operation(operationId = "exportMeetingTranscript",
            summary = "Download what was said, with its times and speakers, as Word or PDF")
    @ApiResponse(responseCode = "200", description = "The transcript",
            content = {@Content(mediaType = DOCX, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "400", description = "The meeting has no transcript yet", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    ResponseEntity<byte[]> transcript(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                      @PathVariable UUID meetingId,
                                      @RequestParam(defaultValue = "DOCX") MeetingService.TranscriptFormat format) {
        byte[] document = meetings.exportTranscript(identity.actorId(), meetingId, format);
        boolean pdf = format == MeetingService.TranscriptFormat.PDF;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("transcript-" + meetingId + (pdf ? ".pdf" : ".docx"), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType(DOCX)).body(document);
    }

    @DeleteMapping("/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteMeeting", summary = "Delete a meeting and its transcript")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Meeting not available", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID meetingId) {
        recordings.delete(identity.actorId(), meetingId);
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
