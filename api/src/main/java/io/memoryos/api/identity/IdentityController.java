package io.memoryos.api.identity;

import io.memoryos.api.identity.contract.CurrentIdentityResponse;
import io.memoryos.api.identity.contract.LanguagePreference;
import io.memoryos.api.identity.contract.PrincipalOptionsResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.PrincipalQuery;
import io.memoryos.iam.PrincipalSearch;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantMembership;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
@Tag(name = "Identity")
class IdentityController {

    private final TenantAccessResolver tenantAccessResolver;
    private final IamAuthorization authorization;
    private final ActorLanguageService languages;
    private final PrincipalSearch principals;

    IdentityController(
            TenantAccessResolver tenantAccessResolver,
            IamAuthorization authorization,
            ActorLanguageService languages,
            PrincipalSearch principals
    ) {
        this.tenantAccessResolver = tenantAccessResolver;
        this.authorization = authorization;
        this.languages = languages;
        this.principals = principals;
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
            @CurrentActor IdentityContext identityContext
    ) {
        TenantMembership membership = tenantAccessResolver.findActiveMembership(identityContext.actorId()).orElse(null);
        if (membership == null) {
            return CurrentIdentityResponse.from(
                    identityContext.actorId().value(),
                    null,
                    Set.of(),
                    Set.of(),
                    0,
                    languages.read(identityContext.actorId())
            );
        }
        Set<IamCapability> capabilities = authorization.effectiveCapabilities(identityContext.actorId());
        Set<IamCapability> scopedCapabilities = authorization.scopedCapabilities(identityContext.actorId());
        return CurrentIdentityResponse.from(
                identityContext.actorId().value(),
                membership,
                capabilities,
                scopedCapabilities,
                authorization.authorizationVersion(identityContext.actorId()),
                languages.read(identityContext.actorId())
        );
    }

    @Operation(operationId = "setCurrentIdentityLanguage", summary = "Set the authenticated account's interface language",
            security = {@SecurityRequirement(name = "browserSession"), @SecurityRequirement(name = "bearerAuth")})
    @PutMapping("/me/language")
    LanguagePreference setLanguage(
            @CurrentActor IdentityContext identityContext,
            @Valid @RequestBody LanguagePreference request
    ) {
        return new LanguagePreference(languages.save(identityContext.actorId(), request.uiLanguage()));
    }

    @Operation(operationId = "searchPrincipals",
            summary = "Search the active members and ordinary Groups of the caller's Tenant to share something with",
            description = "Open to every active member of the Tenant. Returns at most `size` people and `size` Groups whose name "
                    + "(or a person's e-mail) contains the search text; each consumer rechecks what it is given.",
            security = {@SecurityRequirement(name = "browserSession"), @SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(responseCode = "200", description = "Matching people and Groups", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid principal search")
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(responseCode = "403", description = "The actor is not an active member of the Tenant")
    @GetMapping(value = "/principals", produces = MediaType.APPLICATION_JSON_VALUE)
    PrincipalOptionsResponse searchPrincipals(
            @CurrentActor IdentityContext identityContext,
            @Size(max = PrincipalQuery.MAX_SEARCH_LENGTH) @RequestParam(required = false) @Nullable String search,
            @Parameter(schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "50"))
            @Min(1) @Max(PrincipalQuery.MAX_SIZE) @RequestParam(defaultValue = "20") int size
    ) {
        return PrincipalOptionsResponse.from(principals.search(identityContext.actorId(), new PrincipalQuery(search, size)));
    }
}
