package io.memoryos.api.invitation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.memoryos.identity.IdentityContext;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationService;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.invitation.IssuedInvitation;
import io.memoryos.organization.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/invitations")
final class InvitationController {

    static final String BROWSER_REQUEST_HEADER = "X-MemoryOS-CSRF";
    static final String BROWSER_REQUEST_VALUE = "1";
    private static final String API_PROBLEM_SCHEMA = "#/components/schemas/ApiProblem";

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @Operation(
            operationId = "listInvitations",
            summary = "List the current owner's Organization invitations",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "Invitation lifecycle records without plaintext secrets",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = InvitationResponse.class))
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "No accepted authentication is present",
            content = @Content
    )
    @ApiResponse(
            responseCode = "403",
            description = "The actor is not an active Organization owner",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @GetMapping
    List<InvitationResponse> list(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext
    ) {
        return invitations.list(identityContext.actorId()).stream()
                .map(InvitationController::response)
                .toList();
    }

    @Operation(
            operationId = "createInvitation",
            summary = "Create one Organization member invitation",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "201",
            description = "Invitation created; the invitation URL is returned only in this response",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedInvitationResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid email",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "No accepted authentication is present",
            content = @Content
    )
    @ApiResponse(
            responseCode = "403",
            description = "The actor is not an active Organization owner or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "409",
            description = "A conflicting pending invitation already exists",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    IssuedInvitationResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateInvitationRequest.class)
                    )
            )
            @RequestBody CreateInvitationRequest request
    ) {
        requireBrowserMutation(browserRequest);
        return issued(invitations.issue(identityContext.actorId(), request.email()));
    }

    @Operation(
            operationId = "rotateInvitation",
            summary = "Replace the secret of a pending invitation",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "Invitation rotated; the replacement URL is returned only in this response",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedInvitationResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "No accepted authentication is present",
            content = @Content
    )
    @ApiResponse(
            responseCode = "403",
            description = "The actor is not an active Organization owner or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "410",
            description = "Invitation is no longer pending and available",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @PostMapping("/{invitationId}/rotate")
    IssuedInvitationResponse rotate(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @Parameter(description = "Invitation identifier.", required = true)
            @PathVariable UUID invitationId
    ) {
        requireBrowserMutation(browserRequest);
        return issued(invitations.rotate(identityContext.actorId(), invitationId));
    }

    @Operation(
            operationId = "revokeInvitation",
            summary = "Revoke a pending invitation",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(responseCode = "204", description = "Invitation revoked")
    @ApiResponse(
            responseCode = "401",
            description = "No accepted authentication is present",
            content = @Content
    )
    @ApiResponse(
            responseCode = "403",
            description = "The actor is not an active Organization owner or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "410",
            description = "Invitation is no longer pending and available",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{invitationId}")
    void revoke(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @Parameter(description = "Invitation identifier.", required = true)
            @PathVariable UUID invitationId
    ) {
        requireBrowserMutation(browserRequest);
        invitations.revoke(identityContext.actorId(), invitationId);
    }

    @Operation(
            operationId = "getCurrentInvitation",
            summary = "Return the redacted invitation landing context from the browser session"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Active invitation continuation",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CurrentInvitationResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "410",
            description = "No available invitation continuation exists",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @GetMapping("/current")
    CurrentInvitationResponse current(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var session = request.getSession(false);
        var state = session == null
                ? null
                : session.getAttribute(InvitationSessionState.ATTRIBUTE);
        if (!(state instanceof InvitationSessionState invitationState)) {
            throw unavailable();
        }
        try {
            var continuation = invitations.resume(
                    invitationState.invitationId(),
                    new OrganizationId(invitationState.organizationId())
            );
            return new CurrentInvitationResponse(
                    continuation.organizationDisplayName(),
                    continuation.expiresAt(),
                    "/invite/continue"
            );
        } catch (InvitationException exception) {
            session.removeAttribute(InvitationSessionState.ATTRIBUTE);
            throw exception;
        }
    }

    private static IssuedInvitationResponse issued(
            IssuedInvitation invitation
    ) {
        return new IssuedInvitationResponse(
                response(invitation.invitation()),
                "/invite/" + invitation.plaintextSecret()
        );
    }

    private static InvitationResponse response(InvitationView invitation) {
        return new InvitationResponse(
                invitation.id(),
                invitation.email(),
                invitation.status(),
                invitation.createdAt(),
                invitation.expiresAt(),
                invitation.acceptedActorId() == null ? null : invitation.acceptedActorId().value(),
                invitation.acceptedAt(),
                invitation.revokedAt()
        );
    }

    private static void requireBrowserMutation(String browserRequest) {
        if (!BROWSER_REQUEST_VALUE.equals(browserRequest)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "same-origin browser request required");
        }
    }

    private static InvitationException unavailable() {
        return new InvitationException(
                InvitationFailureReason.INVITATION_NOT_AVAILABLE,
                "invitation continuation is not available"
        );
    }

    @Schema(name = "CreateInvitationRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record CreateInvitationRequest(
            @Schema(
                    format = "email",
                    maxLength = 254,
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            String email
    ) {
    }

    @Schema(name = "Invitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record InvitationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID id,
            @Schema(format = "email", requiredMode = Schema.RequiredMode.REQUIRED)
            String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            InvitationStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant expiresAt,
            @Schema(nullable = true)
            UUID acceptedActorId,
            @Schema(nullable = true)
            Instant acceptedAt,
            @Schema(nullable = true)
            Instant revokedAt
    ) {
    }

    @Schema(name = "IssuedInvitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record IssuedInvitationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            InvitationResponse invitation,
            @Schema(
                    pattern = "^/invite/",
                    description = "Relative same-origin capability URL returned only from create or rotate.",
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            String invitationUrl
    ) {
    }

    @Schema(name = "CurrentInvitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record CurrentInvitationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String organizationDisplayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant expiresAt,
            @Schema(pattern = "^/invite/continue", requiredMode = Schema.RequiredMode.REQUIRED)
            String continueUrl
    ) {
    }
}
