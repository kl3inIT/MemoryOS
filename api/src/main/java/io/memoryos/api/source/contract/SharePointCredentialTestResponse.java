package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointCredentialService.TestResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * {@code allSitesReadable} false means the token works but the application cannot list the whole Tenant,
 * which is expected for {@code Sites.Selected} and rules out the "All sites" scope.
 */
@Schema(name = "SharePointCredentialTestResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointCredentialTestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean allSitesReadable,
        @Nullable String tenantHost) {

    public static SharePointCredentialTestResponse from(TestResult result) {
        return new SharePointCredentialTestResponse(result.allSitesReadable(), result.tenantHost());
    }
}
