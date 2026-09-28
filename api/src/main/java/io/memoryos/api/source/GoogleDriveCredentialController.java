package io.memoryos.api.source;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.source.contract.GoogleDriveAuthorizationResponse;
import io.memoryos.api.source.contract.GoogleDriveCredentialResponse;
import io.memoryos.api.source.contract.GoogleDriveServiceAccountRequest;
import io.memoryos.api.source.contract.RevokeGoogleDriveCredentialRequest;
import io.memoryos.api.source.contract.StartGoogleDriveAuthorizationRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAccountClient;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveServiceAccountService;
import io.memoryos.connector.SourceException;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final GoogleDriveServiceAccountService serviceAccounts;

    GoogleDriveCredentialController(GoogleDriveAuthorizationService authorizations,
            GoogleDriveAccountClient accounts, GoogleDriveServiceAccountService serviceAccounts) {
        this.authorizations = authorizations; this.accounts = accounts;
        this.serviceAccounts = serviceAccounts;
    }

    @Operation(operationId = "listGoogleDriveCredentials", summary = "List reusable Tenant-owned Google Drive credentials")
    @GetMapping
    ResponseEntity<List<GoogleDriveCredentialResponse>> list(
            @CurrentActor IdentityContext identity) {
        return ResponseEntity.ok().body(authorizations.list(identity.actorId())
                .stream().map(GoogleDriveCredentialResponse::from).toList());
    }

    @Operation(operationId = "startGoogleDriveAuthorization", summary = "Authorize a reusable Google Drive credential")
    @PostMapping(value = "/authorization", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveAuthorizationResponse> startAuthorization(
            @CurrentActor IdentityContext identity,
            @Valid @RequestBody StartGoogleDriveAuthorizationRequest body, HttpServletRequest request) {
        try (var supplied = accounts.parseClient(body.oauthClientJson())) {
            var preparation = authorizations.prepare(identity.actorId(), body.name(),
                    body.credentialId() == null ? null : new CredentialId(body.credentialId()), body.expectedCredentialRevision(), supplied);
            accounts.requireConfigured();
            try (var client = authorizations.oauthClient(identity.actorId(), preparation)) {
                var state = GoogleDriveAuthorizationSessionState.start(request, identity, preparation);
                return ResponseEntity.ok()
                        .body(new GoogleDriveAuthorizationResponse(accounts.authorizationUrl(client.clientId(), state.consent())));
            }
        }
    }

    @Operation(operationId = "createGoogleDriveServiceAccount",
            summary = "Verify a domain-wide-delegated service account as its admin and store it; nothing is stored when Google rejects it")
    @ApiResponse(responseCode = "201", description = "Stored Google Drive credential", useReturnTypeSchema = true)
    @PostMapping(value = "/service-account", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveCredentialResponse> createServiceAccount(
            @CurrentActor IdentityContext identity,
            @Valid @RequestBody GoogleDriveServiceAccountRequest body) {
        var credentialId = serviceAccounts.create(identity.actorId(), body.name(), body.serviceAccountKeyJson(), body.adminEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(find(identity, credentialId));
    }

    @Operation(operationId = "replaceGoogleDriveServiceAccount",
            summary = "Replace the key and acting admin of the same service account with a revision precondition")
    @ApiResponse(responseCode = "200", description = "Updated Google Drive credential", useReturnTypeSchema = true)
    @PutMapping(value = "/{credentialId}/service-account", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveCredentialResponse> replaceServiceAccount(
            @CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody GoogleDriveServiceAccountRequest body) {
        var id = new CredentialId(credentialId);
        serviceAccounts.replace(identity.actorId(), id, GoogleDriveSourceController.revision(ifMatch), body.name(),
                body.serviceAccountKeyJson(), body.adminEmail());
        return ResponseEntity.ok().body(find(identity, id));
    }

    private GoogleDriveCredentialResponse find(IdentityContext identity, CredentialId credentialId) {
        return authorizations.list(identity.actorId()).stream().filter(view -> view.id().equals(credentialId))
                .findFirst().map(GoogleDriveCredentialResponse::from).orElseThrow(SourceException::notFound);
    }

    @Operation(operationId = "revokeGoogleDriveCredential", summary = "Revoke a shared Google Drive credential and disconnect all attached Sources")
    @PostMapping(value = "/{credentialId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revoke(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @Valid @RequestBody RevokeGoogleDriveCredentialRequest body) {
        accounts.revoke(authorizations.disconnect(identity.actorId(), new CredentialId(credentialId), body.expectedCredentialRevision()));
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "deleteGoogleDriveCredential", summary = "Delete an unused Google Drive credential with a revision precondition")
    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch) {
        authorizations.delete(identity.actorId(), new CredentialId(credentialId), GoogleDriveSourceController.revision(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
