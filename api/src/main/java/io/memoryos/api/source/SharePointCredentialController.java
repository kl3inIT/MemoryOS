package io.memoryos.api.source;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.source.contract.RenameSharePointCredentialRequest;
import io.memoryos.api.source.contract.SharePointCredentialRequest;
import io.memoryos.api.source.contract.SharePointCredentialResponse;
import io.memoryos.api.source.contract.SharePointCredentialTestResponse;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointCredentialService;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/credentials/sharepoint", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Credentials")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid SharePoint credential")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "SharePoint credential unavailable")
@ApiResponse(responseCode = "409", description = "SharePoint credential changed")
@ApiResponse(responseCode = "503", description = "Microsoft did not answer")
final class SharePointCredentialController {
    private final SharePointCredentialService credentials;

    SharePointCredentialController(SharePointCredentialService credentials) {
        this.credentials = credentials;
    }

    @Operation(operationId = "listSharePointCredentials", summary = "List reusable Tenant-owned SharePoint credentials")
    @ApiResponse(responseCode = "200", description = "SharePoint credentials", useReturnTypeSchema = true)
    @GetMapping
    ResponseEntity<List<SharePointCredentialResponse>> list(
            @CurrentActor IdentityContext identity) {
        return ResponseEntity.ok()
                .body(credentials.list(identity.actorId()).stream().map(SharePointCredentialResponse::from).toList());
    }

    @Operation(operationId = "createSharePointCredential",
            summary = "Verify an Entra application with Microsoft and store it; nothing is stored when Microsoft rejects it")
    @ApiResponse(responseCode = "201", description = "Stored SharePoint credential", useReturnTypeSchema = true)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharePointCredentialResponse> create(
            @CurrentActor IdentityContext identity,
            @Valid @RequestBody SharePointCredentialRequest body) {
        try (var draft = draft(body)) {
            var credentialId = credentials.create(identity.actorId(), draft);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(find(identity, credentialId));
        }
    }

    @Operation(operationId = "replaceSharePointCredentialAuthentication",
            summary = "Replace the client secret or certificate of an existing SharePoint credential")
    @ApiResponse(responseCode = "200", description = "Updated SharePoint credential", useReturnTypeSchema = true)
    @PutMapping(value = "/{credentialId}/authentication", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharePointCredentialResponse> replaceAuthentication(
            @CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody SharePointCredentialRequest body) {
        try (var draft = draft(body)) {
            credentials.replaceAuthentication(identity.actorId(), new CredentialId(credentialId),
                    GoogleDriveSourceController.revision(ifMatch), draft);
        }
        return ResponseEntity.ok()
                .body(find(identity, new CredentialId(credentialId)));
    }

    @Operation(operationId = "renameSharePointCredential", summary = "Rename a SharePoint credential with a revision precondition")
    @ApiResponse(responseCode = "204", description = "SharePoint credential renamed", content = @Content)
    @PutMapping(value = "/{credentialId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> rename(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody RenameSharePointCredentialRequest body) {
        credentials.rename(identity.actorId(), new CredentialId(credentialId),
                GoogleDriveSourceController.revision(ifMatch), body.name());
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "testSharePointCredential",
            summary = "Ask Microsoft for a token and read the root site; records the resolved Tenant host")
    @ApiResponse(responseCode = "200", description = "SharePoint credential works", useReturnTypeSchema = true)
    @PostMapping("/{credentialId}/test")
    ResponseEntity<SharePointCredentialTestResponse> test(
            @CurrentActor IdentityContext identity, @PathVariable UUID credentialId) {
        return ResponseEntity.ok()
                .body(SharePointCredentialTestResponse.from(credentials.test(identity.actorId(), new CredentialId(credentialId))));
    }

    @Operation(operationId = "deleteSharePointCredential", summary = "Delete an unused SharePoint credential with a revision precondition")
    @ApiResponse(responseCode = "204", description = "SharePoint credential deleted", content = @Content)
    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch) {
        credentials.delete(identity.actorId(), new CredentialId(credentialId), GoogleDriveSourceController.revision(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private SharePointCredentialResponse find(IdentityContext identity, CredentialId credentialId) {
        return credentials.list(identity.actorId()).stream()
                .filter(view -> view.id().equals(credentialId)).findFirst()
                .map(SharePointCredentialResponse::from)
                .orElseThrow(io.memoryos.connector.SourceException::notFound);
    }

    private static UUID identifier(String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw SharePointException.invalidDirectory();
        }
    }

    private static SharePointCredentialService.Draft draft(SharePointCredentialRequest body) {
        byte[] secret = null;
        byte[] pkcs12 = null;
        char[] password = null;
        try {
            if (body.authMethod() == SharePointProvider.AuthMethod.CLIENT_SECRET) {
                if (body.clientSecret() == null || body.clientSecret().isBlank()) throw SharePointException.invalidSecret();
                secret = body.clientSecret().getBytes(StandardCharsets.UTF_8);
            } else {
                if (body.certificate() == null || body.certificate().isBlank()) {
                    throw SharePointException.invalidCertificate("Upload a PKCS#12 (.pfx) file and its password.",
                            "SharePoint certificate request is missing the keystore");
                }
                try {
                    pkcs12 = Base64.getDecoder().decode(body.certificate());
                } catch (IllegalArgumentException exception) {
                    throw SharePointException.invalidCertificate("The certificate upload is not valid base64.",
                            "SharePoint certificate request is not base64");
                }
                password = (body.certificatePassword() == null ? "" : body.certificatePassword()).toCharArray();
            }
            return new SharePointCredentialService.Draft(body.name(), identifier(body.directoryId()),
                    identifier(body.clientId()), body.cloud(), body.authMethod(), secret, pkcs12, password);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
            if (pkcs12 != null) Arrays.fill(pkcs12, (byte) 0);
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
