package io.memoryos.api.mcp;

import io.memoryos.mcp.McpOAuthProperties;
import io.memoryos.mcp.McpOAuthService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public OAuth Client ID Metadata Document fetched by authorization servers. */
@Hidden
@RestController
final class McpClientMetadataController {
    private final McpOAuthService oauth;

    McpClientMetadataController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping(value = McpOAuthProperties.CLIENT_METADATA_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> document() {
        // Deliberate override of the security default: the public client metadata document is fetched by
        // authorization servers and holds no secret, so shared caches may keep it for an hour.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(oauth.clientMetadataDocument());
    }
}
