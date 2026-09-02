package io.memoryos.api.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import io.memoryos.api.identity.contract.CurrentIdentityResponse;
import io.memoryos.identity.IdentityContext;
import io.memoryos.tenant.TenantAccessResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
@Tag(name = "Identity")
final class IdentityController {

    private final TenantAccessResolver tenantAccessResolver;

    IdentityController(TenantAccessResolver tenantAccessResolver) {
        this.tenantAccessResolver = tenantAccessResolver;
    }

    @Operation(
            operationId = "getCurrentIdentity",
            summary = "Return the authenticated MemoryOS actor",
            description = "Accepts either an existing MemoryOS browser session or a valid bound bearer identity "
                    + "and returns the stable internal ActorId plus its durable Tenant authority projection.",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "The authenticated actor",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CurrentIdentityResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "No accepted authentication is present",
            content = @Content
    )
    @GetMapping("/me")
    CurrentIdentityResponse currentIdentity(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext
    ) {
        return CurrentIdentityResponse.from(
                identityContext.actorId().value(),
                tenantAccessResolver.findActiveMembership(identityContext.actorId()).orElse(null)
        );
    }

}
