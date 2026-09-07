package io.memoryos.api.source;

import io.memoryos.api.source.contract.GoogleDriveAuthorizationResponse;
import io.memoryos.api.source.contract.GoogleDriveCredentialResponse;
import io.memoryos.api.source.contract.RevokeGoogleDriveCredentialRequest;
import io.memoryos.api.source.contract.StartGoogleDriveAuthorizationRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/credentials/google-drive")
@Tag(name = "Credentials")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GoogleDriveCredentialController {
    private final GoogleDriveAuthorizationService authorizations;
    private final GoogleDriveAccountClient accounts;
    private final GoogleDriveOAuthProperties properties;

    GoogleDriveCredentialController(GoogleDriveAuthorizationService authorizations,
            GoogleDriveAccountClient accounts, GoogleDriveOAuthProperties properties) {
        this.authorizations = authorizations; this.accounts = accounts; this.properties = properties;
    }

    @Operation(operationId = "listGoogleDriveCredentials", summary = "List reusable Tenant-owned Google Drive credentials")
    @GetMapping
    ResponseEntity<List<GoogleDriveCredentialResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authorizations.list(identity.actorId())
                .stream().map(GoogleDriveCredentialResponse::from).toList());
    }

    @Operation(operationId = "startGoogleDriveAuthorization", summary = "Authorize a reusable Google Drive credential")
    @PostMapping(value = "/authorization", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveAuthorizationResponse> startAuthorization(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody StartGoogleDriveAuthorizationRequest body, HttpServletRequest request) {
        try (var supplied = accounts.parseClient(body.oauthClientJson())) {
            var preparation = authorizations.prepare(identity.actorId(), body.name(),
                    body.credentialId() == null ? null : new CredentialId(body.credentialId()), body.expectedCredentialRevision(), supplied);
            properties.requireConfigured();
            try (var client = authorizations.oauthClient(identity.actorId(), preparation)) {
                var state = GoogleDriveAuthorizationSessionState.start(request, identity, preparation);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new GoogleDriveAuthorizationResponse(accounts.authorizationUrl(state, client.clientId())));
            }
        }
    }

    @Operation(operationId = "revokeGoogleDriveCredential", summary = "Revoke a shared Google Drive credential and disconnect all attached Sources")
    @PostMapping(value = "/{credentialId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revoke(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID credentialId, @Valid @RequestBody RevokeGoogleDriveCredentialRequest body) {
        accounts.revoke(authorizations.disconnect(identity.actorId(), new CredentialId(credentialId), body.expectedCredentialRevision()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(operationId = "deleteGoogleDriveCredential", summary = "Delete an unused Google Drive credential with a revision precondition")
    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch) {
        authorizations.delete(identity.actorId(), new CredentialId(credentialId), GoogleDriveSourceController.revision(ifMatch));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
