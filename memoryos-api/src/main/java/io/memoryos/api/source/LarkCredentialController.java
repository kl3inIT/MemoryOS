package io.memoryos.api.source;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.source.contract.LarkCredentialRequest;
import io.memoryos.api.source.contract.LarkCredentialResponse;
import io.memoryos.api.source.contract.LarkCredentialTestResponse;
import io.memoryos.api.source.contract.RenameLarkCredentialRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService;
import io.memoryos.connector.SourceException;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
@RequestMapping(value = "/api/credentials/lark", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Credentials")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Lark credential")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Lark credential unavailable")
@ApiResponse(responseCode = "409", description = "Lark credential changed")
@ApiResponse(responseCode = "503", description = "Lark did not answer")
final class LarkCredentialController {
    private final LarkCredentialService credentials;

    LarkCredentialController(LarkCredentialService credentials) {
        this.credentials = credentials;
    }

    @Operation(operationId = "listLarkCredentials", summary = "List reusable Tenant-owned Lark credentials")
    @ApiResponse(responseCode = "200", description = "Lark credentials", useReturnTypeSchema = true)
    @GetMapping
    ResponseEntity<List<LarkCredentialResponse>> list(@CurrentActor IdentityContext identity) {
        return ResponseEntity.ok()
                .body(credentials.list(identity.actorId()).stream().map(LarkCredentialResponse::from).toList());
    }

    @Operation(operationId = "createLarkCredential",
            summary = "Verify a Lark custom app with Lark and store it; nothing is stored when Lark rejects it")
    @ApiResponse(responseCode = "201", description = "Stored Lark credential", useReturnTypeSchema = true)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LarkCredentialResponse> create(@CurrentActor IdentityContext identity,
            @Valid @RequestBody LarkCredentialRequest body) {
        try (var draft = draft(body)) {
            var credentialId = credentials.create(identity.actorId(), draft);
            return ResponseEntity.status(HttpStatus.CREATED).body(find(identity, credentialId));
        }
    }

    @Operation(operationId = "replaceLarkCredentialSecret",
            summary = "Replace the App Secret of an existing Lark credential of the same app")
    @ApiResponse(responseCode = "200", description = "Updated Lark credential", useReturnTypeSchema = true)
    @PutMapping(value = "/{credentialId}/secret", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LarkCredentialResponse> replaceSecret(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody LarkCredentialRequest body) {
        try (var draft = draft(body)) {
            credentials.replaceSecret(identity.actorId(), new CredentialId(credentialId),
                    GoogleDriveSourceController.revision(ifMatch), draft);
        }
        return ResponseEntity.ok().body(find(identity, new CredentialId(credentialId)));
    }

    @Operation(operationId = "renameLarkCredential", summary = "Rename a Lark credential with a revision precondition")
    @ApiResponse(responseCode = "204", description = "Lark credential renamed", content = @Content)
    @PutMapping(value = "/{credentialId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> rename(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody RenameLarkCredentialRequest body) {
        credentials.rename(identity.actorId(), new CredentialId(credentialId),
                GoogleDriveSourceController.revision(ifMatch), body.name());
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "testLarkCredential",
            summary = "Ask Lark for a tenant token, read the bot and probe the read scopes; records what Lark said")
    @ApiResponse(responseCode = "200", description = "Lark credential works", useReturnTypeSchema = true)
    @PostMapping("/{credentialId}/test")
    ResponseEntity<LarkCredentialTestResponse> test(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId) {
        return ResponseEntity.ok().body(LarkCredentialTestResponse.from(
                credentials.test(identity.actorId(), new CredentialId(credentialId))));
    }

    @Operation(operationId = "deleteLarkCredential", summary = "Delete an unused Lark credential with a revision precondition")
    @ApiResponse(responseCode = "204", description = "Lark credential deleted", content = @Content)
    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId, @RequestHeader("If-Match") String ifMatch) {
        credentials.delete(identity.actorId(), new CredentialId(credentialId), GoogleDriveSourceController.revision(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private LarkCredentialResponse find(IdentityContext identity, CredentialId credentialId) {
        return credentials.list(identity.actorId()).stream()
                .filter(view -> view.id().equals(credentialId)).findFirst()
                .map(LarkCredentialResponse::from)
                .orElseThrow(SourceException::notFound);
    }

    private static LarkCredentialService.Draft draft(LarkCredentialRequest body) {
        byte[] secret = body.appSecret().strip().getBytes(StandardCharsets.UTF_8);
        try {
            return new LarkCredentialService.Draft(body.name(), body.region(), body.appId(), secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
