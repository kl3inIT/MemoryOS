package io.memoryos.api.identityprovider;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.identityprovider.contract.CreateIdentityProviderRequest;
import io.memoryos.api.identityprovider.contract.DiscoverIdentityProviderRequest;
import io.memoryos.api.identityprovider.contract.DiscoveredProviderResponse;
import io.memoryos.api.identityprovider.contract.IdentityProviderResponse;
import io.memoryos.api.identityprovider.contract.UpdateIdentityProviderRequest;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.IdentityProviderAdministration;
import io.memoryos.iam.IdentityProviderCommand;
import io.memoryos.iam.IdentityProviderUpdate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/identity-providers")
@Tag(name = "Identity Providers")
final class IdentityProviderController {

    private final IdentityProviderAdministration administration;
    private final String issuerBase;

    IdentityProviderController(
            IdentityProviderAdministration administration,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerBase
    ) {
        this.administration = Objects.requireNonNull(administration, "administration must not be null");
        this.issuerBase = Objects.requireNonNull(issuerBase, "issuerBase must not be null");
    }

    @Operation(
            operationId = "listIdentityProviders",
            summary = "List upstream OIDC identity providers on the configured realm",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "Configured OIDC identity providers without secrets",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = IdentityProviderResponse.class))
            )
    )
    @ApiResponse(
            responseCode = "403",
            description = "The actor lacks system administration authority"
    )
    @GetMapping
    List<IdentityProviderResponse> list(
            @CurrentActor IdentityContext identityContext
    ) {
        return administration.list(identityContext.actorId()).stream()
                .map(view -> IdentityProviderResponse.from(view, issuerBase))
                .toList();
    }

    @Operation(
            operationId = "discoverIdentityProvider",
            summary = "Resolve OIDC endpoints from an issuer discovery document",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "Resolved provider endpoints",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DiscoveredProviderResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "The issuer did not return a valid discovery document"
    )
    @PostMapping("/discovery")
    DiscoveredProviderResponse discover(
            @CurrentActor IdentityContext identityContext,
            @Valid @RequestBody DiscoverIdentityProviderRequest request
    ) {
        return DiscoveredProviderResponse.from(
                administration.discover(identityContext.actorId(), request.issuerUrl())
        );
    }

    @Operation(
            operationId = "createIdentityProvider",
            summary = "Create an upstream OIDC identity provider",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "201",
            description = "The created identity provider",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IdentityProviderResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "409",
            description = "An identity provider with this alias already exists"
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    IdentityProviderResponse create(
            @CurrentActor IdentityContext identityContext,
            @Valid @RequestBody CreateIdentityProviderRequest request
    ) {
        var view = administration.create(identityContext.actorId(), new IdentityProviderCommand(
                request.alias(),
                request.displayName(),
                request.issuerUrl(),
                request.clientId(),
                request.clientSecret(),
                request.jitAllowed()
        ));
        return IdentityProviderResponse.from(view, issuerBase);
    }

    @Operation(
            operationId = "updateIdentityProvider",
            summary = "Update an upstream OIDC identity provider",
            description = "A null alias or issuerUrl keeps the stored value; a null clientSecret keeps the stored secret.",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "The updated identity provider",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IdentityProviderResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "The identity provider was not found"
    )
    @PutMapping("/{alias}")
    IdentityProviderResponse update(
            @CurrentActor IdentityContext identityContext,
            @PathVariable @Size(max = 128) String alias,
            @Valid @RequestBody UpdateIdentityProviderRequest request
    ) {
        var view = administration.update(identityContext.actorId(), alias, new IdentityProviderUpdate(
                request.alias(),
                request.displayName(),
                request.issuerUrl(),
                request.clientId(),
                request.clientSecret(),
                request.enabled(),
                request.jitAllowed()
        ));
        return IdentityProviderResponse.from(view, issuerBase);
    }

    @Operation(
            operationId = "deleteIdentityProvider",
            summary = "Delete an upstream identity provider and its JIT allowlist entry",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(responseCode = "204", description = "The identity provider was deleted")
    @ApiResponse(
            responseCode = "404",
            description = "The identity provider was not found"
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{alias}")
    void delete(
            @CurrentActor IdentityContext identityContext,
            @PathVariable @Size(max = 128) String alias
    ) {
        administration.delete(identityContext.actorId(), alias);
    }
}
