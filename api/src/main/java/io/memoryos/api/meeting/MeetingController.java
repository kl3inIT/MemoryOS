package io.memoryos.api.meeting;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.meeting.contract.AcceptMeetingCorrectionRequest;
import io.memoryos.api.meeting.contract.MeetingBookmarkRequest;
import io.memoryos.api.meeting.contract.MeetingCorrectionRunRequest;
import io.memoryos.api.meeting.contract.MeetingCorrectionRunResponse;
import io.memoryos.api.meeting.contract.MeetingCreateRequest;
import io.memoryos.api.meeting.contract.MeetingDetailResponse;
import io.memoryos.api.meeting.contract.MeetingHeadingRequest;
import io.memoryos.api.meeting.contract.MeetingHeadingResponse;
import io.memoryos.api.meeting.contract.MeetingItemRequest;
import io.memoryos.api.meeting.contract.MeetingLibraryFileResponse;
import io.memoryos.api.meeting.contract.MeetingMinutesItemRequest;
import io.memoryos.api.meeting.contract.MeetingMinutesSummaryRequest;
import io.memoryos.api.meeting.contract.MeetingNewMinutesItemRequest;
import io.memoryos.api.meeting.contract.MeetingNotesRequest;
import io.memoryos.api.meeting.contract.MeetingRecordingRequest;
import io.memoryos.api.meeting.contract.MeetingRecordingReservationResponse;
import io.memoryos.api.meeting.contract.MeetingShareRequest;
import io.memoryos.api.meeting.contract.MeetingSpeakerRequest;
import io.memoryos.api.meeting.contract.MeetingSummaryResponse;
import io.memoryos.api.meeting.contract.MeetingTicketRequest;
import io.memoryos.api.meeting.contract.MeetingTicketResponse;
import io.memoryos.api.meeting.contract.MeetingTranscriberResponse;
import io.memoryos.api.meeting.contract.MeetingUpdateRequest;
import io.memoryos.api.meeting.contract.MeetingWordCorrectionRequest;
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
import io.memoryos.iam.IdentityContext;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingCorrectionService;
import io.memoryos.meeting.MeetingException;
import io.memoryos.meeting.MeetingLibraryService;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@ApiResponse(responseCode = "400", description = "Invalid meeting request")
@ApiResponse(responseCode = "403", description = "Chat access, Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class MeetingController {
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MeetingService meetings;
    private final MeetingRecordingService recordings;
    private final MeetingLibraryService library;
    private final VoiceTicketStore tickets;
    private final MeetingCorrectionService corrections;

    MeetingController(MeetingService meetings, MeetingRecordingService recordings, MeetingLibraryService library,
                      VoiceTicketStore tickets, MeetingCorrectionService corrections) {
        this.meetings = meetings;
        this.recordings = recordings;
        this.library = library;
        this.tickets = tickets;
        this.corrections = corrections;
    }

    @GetMapping
    @Operation(operationId = "listMeetings", summary = "List the current member's meetings, newest first")
    @ApiResponse(responseCode = "200", description = "Meetings", useReturnTypeSchema = true)
    List<MeetingSummaryResponse> list(@CurrentActor IdentityContext identity) {
        return meetings.list(identity.actorId()).stream().map(MeetingSummaryResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createMeeting", summary = "Create a meeting to record")
    @ApiResponse(responseCode = "201", description = "The new meeting", useReturnTypeSchema = true)
    MeetingDetailResponse create(@CurrentActor IdentityContext identity,
                          @RequestBody MeetingCreateRequest body) {
        return MeetingDetailResponse.from(meetings.create(identity.actorId(), new Meeting.Draft(body.title(), body.kind(),
                body.language(), body.participants(), body.terms())));
    }

    @GetMapping("/{meetingId}")
    @Operation(operationId = "getMeeting", summary = "Read one of the current member's meetings with its transcript")
    @ApiResponse(responseCode = "200", description = "The meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingDetailResponse get(@CurrentActor IdentityContext identity,
                       @PathVariable UUID meetingId) {
        return MeetingDetailResponse.from(meetings.get(identity.actorId(), meetingId));
    }

    @PutMapping(value = "/{meetingId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateMeetingNotes", summary = "Replace the owner's private notes")
    @ApiResponse(responseCode = "200", description = "The notes as stored and the new revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    @ApiResponse(responseCode = "409", description = "The meeting changed")
    MeetingNotesResponse notes(@CurrentActor IdentityContext identity,
                               @PathVariable UUID meetingId, @RequestBody MeetingNotesRequest body) {
        return MeetingNotesResponse.from(meetings.updateNotes(identity.actorId(), meetingId, body.notes(),
                body.revision()));
    }

    @PutMapping(value = "/{meetingId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateMeeting", summary = "Rename the meeting and say who was in it")
    @ApiResponse(responseCode = "200", description = "The name and the people as stored, and the new revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingParticularsResponse update(@CurrentActor IdentityContext identity,
                                      @PathVariable UUID meetingId, @RequestBody MeetingUpdateRequest body) {
        return MeetingParticularsResponse.from(meetings.updateDetails(identity.actorId(), meetingId, body.title(),
                body.participants()));
    }

    @GetMapping("/{meetingId}/minutes/heading")
    @Operation(operationId = "getMeetingMinutesHeading", summary = "The biên bản heading as the owner last saved it")
    @ApiResponse(responseCode = "200", description = "The heading", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingHeadingResponse heading(@CurrentActor IdentityContext identity,
                            @PathVariable UUID meetingId) {
        return meetings.heading(identity.actorId(), meetingId).map(MeetingHeadingResponse::from)
                .or(() -> meetings.carriedHeading(identity.actorId()).map(last -> MeetingHeadingResponse.from(last, false)))
                .orElseGet(MeetingHeadingResponse::none);
    }

    @PutMapping(value = "/{meetingId}/minutes/heading", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "saveMeetingMinutesHeading", summary = "Keep the biên bản heading the owner typed")
    @ApiResponse(responseCode = "200", description = "The heading as stored", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingHeadingResponse saveHeading(@CurrentActor IdentityContext identity,
                                @PathVariable UUID meetingId, @RequestBody MeetingHeadingRequest body) {
        return MeetingHeadingResponse.from(meetings.saveHeading(identity.actorId(), meetingId, body.toHeading()));
    }

    @PutMapping(value = "/{meetingId}/speakers/{track}/{label}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "nameMeetingSpeaker", summary = "Name a speaker of a meeting track")
    @ApiResponse(responseCode = "200", description = "The speaker as now named", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or speaker not available")
    MeetingSpeakerResponse speaker(@CurrentActor IdentityContext identity,
                                   @PathVariable UUID meetingId, @PathVariable Meeting.Track track,
                                   @PathVariable String label, @RequestBody MeetingSpeakerRequest body) {
        return MeetingSpeakerResponse.from(meetings.nameSpeaker(identity.actorId(), meetingId, track, label,
                body.name()));
    }

    @DeleteMapping("/{meetingId}/speakers/{track}/{label}/suggestion")
    @Operation(operationId = "dismissMeetingSpeakerSuggestion", summary = "Keep the automatic label for a speaker")
    @ApiResponse(responseCode = "200", description = "The speaker, no longer offered a name", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or speaker not available")
    MeetingSpeakerResponse dismissSuggestion(@CurrentActor IdentityContext identity,
                                             @PathVariable UUID meetingId, @PathVariable Meeting.Track track,
                                             @PathVariable String label) {
        return MeetingSpeakerResponse.from(meetings.dismissSpeakerSuggestion(identity.actorId(), meetingId, track,
                label));
    }

    @PostMapping("/{meetingId}/end")
    @Operation(operationId = "endMeeting", summary = "End recording")
    @ApiResponse(responseCode = "200", description = "The ended meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingDetailResponse end(@CurrentActor IdentityContext identity,
                       @PathVariable UUID meetingId) {
        return MeetingDetailResponse.from(meetings.end(identity.actorId(), meetingId));
    }

    @PutMapping("/{meetingId}/utterances/{utteranceId}/star")
    @Operation(operationId = "starMeetingUtterance", summary = "Mark one line as one the caller cares about")
    @ApiResponse(responseCode = "200", description = "Every line the caller starred in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available")
    List<UUID> star(@CurrentActor IdentityContext identity,
                    @PathVariable UUID meetingId, @PathVariable UUID utteranceId) {
        return meetings.star(identity.actorId(), meetingId, utteranceId, true);
    }

    @DeleteMapping("/{meetingId}/utterances/{utteranceId}/star")
    @Operation(operationId = "unstarMeetingUtterance", summary = "Take the caller's mark off a line")
    @ApiResponse(responseCode = "200", description = "Every line the caller still has starred in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available")
    List<UUID> unstar(@CurrentActor IdentityContext identity,
                      @PathVariable UUID meetingId, @PathVariable UUID utteranceId) {
        return meetings.star(identity.actorId(), meetingId, utteranceId, false);
    }

    @PostMapping(value = "/{meetingId}/bookmarks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "bookmarkMeetingMoment",
            summary = "Mark the moment the caller is at, while the meeting is still running")
    @ApiResponse(responseCode = "200", description = "Every mark the caller left in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    List<MeetingBookmarkResponse> bookmark(@CurrentActor IdentityContext identity,
                                           @PathVariable UUID meetingId, @Valid @RequestBody MeetingBookmarkRequest body) {
        return meetings.bookmark(identity.actorId(), meetingId, body.atMs(), body.label()).stream()
                .map(MeetingBookmarkResponse::from).toList();
    }

    @DeleteMapping("/{meetingId}/bookmarks/{bookmarkId}")
    @Operation(operationId = "removeMeetingBookmark", summary = "Take back one of the caller's own marks")
    @ApiResponse(responseCode = "200", description = "Every mark the caller still has in this meeting", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or mark not available")
    List<MeetingBookmarkResponse> removeBookmark(@CurrentActor IdentityContext identity,
                                                 @PathVariable UUID meetingId, @PathVariable UUID bookmarkId) {
        return meetings.removeBookmark(identity.actorId(), meetingId, bookmarkId).stream()
                .map(MeetingBookmarkResponse::from).toList();
    }

    @PostMapping("/{meetingId}/corrections")
    @Operation(operationId = "proposeMeetingCorrections",
            summary = "Ask a model what the speech provider probably meant at each uncertain stretch")
    @ApiResponse(responseCode = "200", description = "What the pass proposed; the transcript is unchanged",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    @ApiResponse(responseCode = "409", description = "A pass is already running")
    MeetingCorrectionRunResponse propose(@CurrentActor IdentityContext identity,
                        @PathVariable UUID meetingId) {
        return MeetingCorrectionRunResponse.from(corrections.propose(identity.actorId(), meetingId));
    }

    @GetMapping("/{meetingId}/corrections")
    @Operation(operationId = "listMeetingCorrections",
            summary = "Every proposal made for this meeting, decided or not")
    @ApiResponse(responseCode = "200", description = "The proposals", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    List<MeetingCorrectionResponse> corrections(@CurrentActor IdentityContext identity,
                                                @PathVariable UUID meetingId) {
        return corrections.corrections(identity.actorId(), meetingId).stream().map(MeetingCorrectionResponse::from)
                .toList();
    }

    @PostMapping(value = "/{meetingId}/corrections/{correctionId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptMeetingCorrection",
            summary = "Put the proposed words, or the caller's own, into the transcript")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the accepted proposal", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available")
    @ApiResponse(responseCode = "409", description = "The line moved since the proposal was made")
    MeetingCorrectionAppliedResponse accept(@CurrentActor IdentityContext identity,
                                            @PathVariable UUID meetingId, @PathVariable UUID correctionId,
                                            @RequestBody AcceptMeetingCorrectionRequest body) {
        return MeetingCorrectionAppliedResponse.from(corrections.accept(identity.actorId(), meetingId, correctionId,
                body.text()));
    }

    @PostMapping(value = "/{meetingId}/utterances/{utteranceId}/corrections",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "correctMeetingWords",
            summary = "Write what was said at a stretch the provider was unsure of")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the correction recorded for it", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or line not available")
    @ApiResponse(responseCode = "409", description = "Still recording, or the stretch is no longer marked")
    MeetingCorrectionAppliedResponse correctWords(@CurrentActor IdentityContext identity,
                                                  @PathVariable UUID meetingId, @PathVariable UUID utteranceId,
                                                  @RequestBody MeetingWordCorrectionRequest body) {
        return MeetingCorrectionAppliedResponse.from(corrections.correctByHand(identity.actorId(), meetingId,
                utteranceId, body.start(), body.end(), body.text() == null ? "" : body.text()));
    }

    @PostMapping("/{meetingId}/corrections/{correctionId}/keep")
    @Operation(operationId = "keepMeetingWording", summary = "Decline a proposal and keep what the provider heard")
    @ApiResponse(responseCode = "200", description = "The declined proposal; no line changed", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available")
    MeetingCorrectionResponse keep(@CurrentActor IdentityContext identity,
                                   @PathVariable UUID meetingId, @PathVariable UUID correctionId) {
        return MeetingCorrectionResponse.from(corrections.keep(identity.actorId(), meetingId, correctionId));
    }

    @PostMapping(value = "/{meetingId}/corrections/accept-all", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptAllMeetingCorrections", summary = "Accept everything a pass proposed and has not decided")
    @ApiResponse(responseCode = "200", description = "The meeting with every accepted line rewritten", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingDetailResponse acceptAll(@CurrentActor IdentityContext identity,
                             @PathVariable UUID meetingId, @RequestBody MeetingCorrectionRunRequest body) {
        return MeetingDetailResponse.from(corrections.acceptAll(identity.actorId(), meetingId, body.runId()));
    }

    @PostMapping(value = "/{meetingId}/corrections/revert-all", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revertAllMeetingCorrections", summary = "Take back everything one pass put in")
    @ApiResponse(responseCode = "200", description = "The meeting with every line of that pass restored", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    @ApiResponse(responseCode = "409", description = "A line changed again after the pass")
    MeetingDetailResponse revertAll(@CurrentActor IdentityContext identity,
                             @PathVariable UUID meetingId, @RequestBody MeetingCorrectionRunRequest body) {
        return MeetingDetailResponse.from(corrections.revertAll(identity.actorId(), meetingId, body.runId()));
    }

    @PostMapping("/{meetingId}/corrections/{correctionId}/revert")
    @Operation(operationId = "revertMeetingCorrection", summary = "Put back what the line said before this proposal")
    @ApiResponse(responseCode = "200", description = "The line as it now reads and the reverted proposal", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or proposal not available")
    @ApiResponse(responseCode = "409", description = "The line changed again after this proposal")
    MeetingCorrectionAppliedResponse revert(@CurrentActor IdentityContext identity,
                                            @PathVariable UUID meetingId, @PathVariable UUID correctionId) {
        return MeetingCorrectionAppliedResponse.from(corrections.revert(identity.actorId(), meetingId, correctionId));
    }

    @PostMapping("/{meetingId}/minutes")
    @Operation(operationId = "rerunMeetingMinutes", summary = "Write the minutes again after the meeting changed")
    @ApiResponse(responseCode = "200", description = "The meeting, with its minutes queued", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "The minutes were corrected by hand; say so to discard that work")
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingDetailResponse minutes(@CurrentActor IdentityContext identity,
                           @PathVariable UUID meetingId,
                           @Parameter(description = "Required once the minutes were corrected by hand, because a "
                                   + "rerun writes them again and throws that work away")
                           @RequestParam(defaultValue = "false") boolean discardEdits) {
        return MeetingDetailResponse.from(library.rerunMinutes(identity.actorId(), meetingId, discardEdits));
    }

    @PutMapping(value = "/{meetingId}/minutes/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "markMeetingMinutesItem", summary = "Tick off a task the minutes found")
    @ApiResponse(responseCode = "200", description = "The item as it now reads", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting or item not available")
    MeetingMinutesItemResponse item(@CurrentActor IdentityContext identity,
                                    @PathVariable UUID meetingId, @PathVariable UUID itemId,
                                    @RequestBody MeetingItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.markItem(identity.actorId(), meetingId, itemId, body.done()));
    }

    @PutMapping(value = "/{meetingId}/shares", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "shareMeeting", summary = "Say who else may read this meeting")
    @ApiResponse(responseCode = "200", description = "Everyone the meeting is now shared with", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    List<MeetingReaderResponse> share(@CurrentActor IdentityContext identity,
                                      @PathVariable UUID meetingId, @RequestBody MeetingShareRequest body) {
        return meetings.share(identity.actorId(), meetingId, body.members(), body.groups()).stream()
                .map(MeetingReaderResponse::from).toList();
    }

    @PostMapping("/{meetingId}/library")
    @Operation(operationId = "publishMeetingMinutes",
            summary = "Take the minutes into the caller's file library so a conversation can use them")
    @ApiResponse(responseCode = "200", description = "The file", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingLibraryFileResponse publish(@CurrentActor IdentityContext identity,
                                @PathVariable UUID meetingId) {
        var file = library.publish(identity.actorId(), meetingId);
        return new MeetingLibraryFileResponse(file.fileId(), file.filename(), file.status());
    }

    @GetMapping("/transcribers")
    @Operation(operationId = "listMeetingTranscribers",
            summary = "The speech connections a recording may be transcribed with, the Tenant's own first")
    @ApiResponse(responseCode = "200", description = "Transcribers", useReturnTypeSchema = true)
    List<MeetingTranscriberResponse> transcribers(@CurrentActor IdentityContext identity) {
        return recordings.transcribers(identity.actorId()).stream().map(MeetingTranscriberResponse::from).toList();
    }

    @PostMapping(value = "/{meetingId}/recording", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reserveMeetingRecording", summary = "Reserve storage for a recording of this meeting")
    @ApiResponse(responseCode = "200", description = "Where to send the recording", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    @ApiResponse(responseCode = "409", description = "The meeting is no longer recording")
    MeetingRecordingReservationResponse reserve(@CurrentActor IdentityContext identity,
                                @PathVariable UUID meetingId, @RequestBody MeetingRecordingRequest body) {
        return MeetingRecordingReservationResponse.from(recordings.reserve(identity.actorId(), meetingId,
                new MeetingRecordingService.Upload(body.filename(), body.mediaType(), body.sizeBytes(), body.sha256(),
                        body.provider())));
    }

    @PostMapping("/{meetingId}/recording/finalize")
    @Operation(operationId = "finalizeMeetingRecording", summary = "Accept the uploaded recording and queue its transcript")
    @ApiResponse(responseCode = "200", description = "The meeting, with its recording queued", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingDetailResponse finalizeRecording(@CurrentActor IdentityContext identity,
                                     @PathVariable UUID meetingId) {
        return MeetingDetailResponse.from(recordings.finalizeUpload(identity.actorId(), meetingId));
    }

    @PostMapping(value = "/{meetingId}/minutes/export", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {DOCX, MediaType.APPLICATION_PDF_VALUE})
    @Operation(operationId = "exportMeetingMinutes", summary = "Download the minutes as a Vietnamese biên bản, in Word or as a PDF")
    @ApiResponse(responseCode = "200", description = "The biên bản",
            content = {@Content(mediaType = DOCX, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    ResponseEntity<byte[]> export(@CurrentActor IdentityContext identity,
                                  @PathVariable UUID meetingId, @RequestBody MeetingHeadingRequest body,
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
    @ApiResponse(responseCode = "400", description = "The minutes are not written yet")
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingMinutesSummaryResponse editSummary(@CurrentActor IdentityContext identity,
                                              @PathVariable UUID meetingId, @Valid @RequestBody MeetingMinutesSummaryRequest body) {
        return MeetingMinutesSummaryResponse.from(meetings.editSummary(identity.actorId(), meetingId, body.summary()));
    }

    @PutMapping(value = "/{meetingId}/minutes/items/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editMeetingMinutesItem",
            summary = "Rewrite one decision or one piece of work, its owner and its deadline")
    @ApiResponse(responseCode = "200", description = "The item as it now reads", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "A decision has no owner and no deadline")
    @ApiResponse(responseCode = "404", description = "Meeting or item not available")
    MeetingMinutesItemResponse editItem(@CurrentActor IdentityContext identity,
                                        @PathVariable UUID meetingId, @PathVariable UUID itemId,
                                        @Valid @RequestBody MeetingMinutesItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.editItem(identity.actorId(), meetingId, itemId, body.text(),
                body.owner(), body.due()));
    }

    @PostMapping(value = "/{meetingId}/minutes/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addMeetingMinutesItem", summary = "Write in a decision or a piece of work the model missed")
    @ApiResponse(responseCode = "200", description = "The item written in, placed after the others of its kind", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    MeetingMinutesItemResponse addItem(@CurrentActor IdentityContext identity,
                                       @PathVariable UUID meetingId, @Valid @RequestBody MeetingNewMinutesItemRequest body) {
        return MeetingMinutesItemResponse.from(meetings.addItem(identity.actorId(), meetingId, body.kind(),
                body.text(), body.owner(), body.due()));
    }

    @DeleteMapping("/{meetingId}/minutes/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeMeetingMinutesItem", summary = "Take a decision or a piece of work out of the minutes")
    @ApiResponse(responseCode = "204", description = "Removed; the minutes now count as the owner's words")
    @ApiResponse(responseCode = "404", description = "Meeting or item not available")
    void removeItem(@CurrentActor IdentityContext identity,
                    @PathVariable UUID meetingId, @PathVariable UUID itemId) {
        meetings.removeItem(identity.actorId(), meetingId, itemId);
    }

    @GetMapping("/{meetingId}/transcript")
    @Operation(operationId = "exportMeetingTranscript",
            summary = "Download what was said, with its times and speakers, as Word or PDF")
    @ApiResponse(responseCode = "200", description = "The transcript",
            content = {@Content(mediaType = DOCX, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "400", description = "The meeting has no transcript yet")
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    ResponseEntity<byte[]> transcript(@CurrentActor IdentityContext identity,
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
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    void delete(@CurrentActor IdentityContext identity, @PathVariable UUID meetingId) {
        recordings.delete(identity.actorId(), meetingId);
    }

    @PostMapping(value = "/{meetingId}/tickets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createMeetingTicket",
            summary = "Issue a 60-second single-use ticket for one track's recording WebSocket")
    @ApiResponse(responseCode = "200", description = "Ticket", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Meeting not available")
    @ApiResponse(responseCode = "409", description = "The meeting has ended")
    MeetingTicketResponse ticket(@CurrentActor IdentityContext identity,
                          @PathVariable UUID meetingId, @RequestBody MeetingTicketRequest body) {
        if (body.track() == null) throw MeetingException.invalid("A track is required.");
        meetings.requireRecordable(identity.actorId(), meetingId, body.track());
        var issued = tickets.issue(identity.actorId(), MeetingStreamWebSocketHandler.scope(meetingId, body.track()));
        return new MeetingTicketResponse(issued.value(), issued.expiresAt());
    }
}
