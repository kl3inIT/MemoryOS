package io.memoryos.api.source;

import io.memoryos.api.source.contract.GoogleDriveAclFileResponse;
import io.memoryos.api.source.contract.GoogleDriveAclPageResponse;
import io.memoryos.connector.GoogleDriveAclService;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources/{sourceId}/google-drive/acl")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GoogleDriveAclController {
    private final GoogleDriveAclService acls;

    GoogleDriveAclController(GoogleDriveAclService acls) { this.acls = acls; }

    @Operation(operationId = "listGoogleDriveAcl", summary = "List persisted Google Drive permission observation summaries")
    @GetMapping
    ResponseEntity<GoogleDriveAclPageResponse> list(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sourceId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 256) @Nullable String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(GoogleDriveAclPageResponse.from(
                acls.list(identity.actorId(), new SourceId(sourceId), new GoogleDriveAclService.Query(cursor, size, query))));
    }

    @Operation(operationId = "getGoogleDriveAcl", summary = "Get retained provider permissions and lifecycle context for one known Drive file")
    @GetMapping("/{fileId}")
    ResponseEntity<GoogleDriveAclFileResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sourceId, @PathVariable String fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(GoogleDriveAclFileResponse.from(
                acls.get(identity.actorId(), new SourceId(sourceId), fileId)));
    }
}
