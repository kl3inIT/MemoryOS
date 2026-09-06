package io.memoryos.api.identity;

import io.memoryos.api.identity.contract.CurrentIdentityResponse;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantMembership;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
@Tag(name = "Identity")
class IdentityController {

    private final TenantAccessResolver tenantAccessResolver;
    private final IamAuthorization authorization;

    IdentityController(
            TenantAccessResolver tenantAccessResolver,
            IamAuthorization authorization
    ) {
        this.tenantAccessResolver = tenantAccessResolver;
        this.authorization = authorization;
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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/me")
    CurrentIdentityResponse currentIdentity(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext
    ) {
        TenantMembership membership = tenantAccessResolver.findActiveMembership(identityContext.actorId()).orElse(null);
        if (membership == null) {
            return CurrentIdentityResponse.from(
                    identityContext.actorId().value(),
                    null,
                    Set.of(),
                    Set.of(),
                    0
            );
        }
        Set<IamCapability> capabilities = authorization.effectiveCapabilities(identityContext.actorId());
        Set<IamCapability> scopedCapabilities = authorization.scopedCapabilities(identityContext.actorId());
        return CurrentIdentityResponse.from(
                identityContext.actorId().value(),
                membership,
                capabilities,
                scopedCapabilities,
                authorization.authorizationVersion(identityContext.actorId())
        );
    }
}
