package io.memoryos.api.invitation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import io.memoryos.api.invitation.contract.CreateInvitationRequest;
import io.memoryos.api.invitation.contract.CurrentInvitationResponse;
import io.memoryos.api.invitation.contract.InvitationPageResponse;
import io.memoryos.api.invitation.contract.InvitationDeliveryResponse;
import io.memoryos.api.invitation.contract.InvitationResponse;
import io.memoryos.api.invitation.contract.IssuedInvitationResponse;
import io.memoryos.identity.IdentityContext;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationPage;
import io.memoryos.invitation.InvitationQuery;
import io.memoryos.invitation.InvitationSort;
import io.memoryos.invitation.InvitationService;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.invitation.IssuedInvitation;
import io.memoryos.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/invitations")
@Tag(name = "Invitations")
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
            summary = "List the current owner's Tenant invitations",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "A bounded page of invitation lifecycle records without plaintext secrets",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = InvitationPageResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid invitation list query",
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
            description = "The actor is not an active Tenant owner",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @GetMapping
    InvitationPageResponse list(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @RequestParam(required = false) InvitationStatus status,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "CREATED_AT_DESC") InvitationSort sort,
            @Parameter(schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "20") int size
    ) {
        InvitationQuery query;
        try {
            query = new InvitationQuery(status, email, sort, page, size);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        return page(invitations.list(identityContext.actorId(), query));
    }

    @Operation(
            operationId = "createInvitation",
            summary = "Create one Tenant member invitation",
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
            description = "The actor is not an active Tenant owner or the same-origin header is missing",
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
    @ApiResponse(
            responseCode = "503",
            description = "Keycloak recipient activation is temporarily unavailable",
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
            description = "The actor is not an active Tenant owner or the same-origin header is missing",
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
            description = "The actor is not an active Tenant owner or the same-origin header is missing",
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
    @PostMapping("/{invitationId}/revoke")
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
                    new TenantId(invitationState.tenantId())
            );
            return new CurrentInvitationResponse(
                    continuation.tenantDisplayName(),
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
                "/invite/" + invitation.plaintextSecret(),
                InvitationDeliveryResponse.valueOf(invitation.delivery().name())
        );
    }

    private static InvitationPageResponse page(InvitationPage invitations) {
        return new InvitationPageResponse(
                invitations.items().stream()
                        .map(InvitationController::response)
                        .toList(),
                invitations.page(),
                invitations.size(),
                invitations.totalItems(),
                invitations.totalPages()
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

}
