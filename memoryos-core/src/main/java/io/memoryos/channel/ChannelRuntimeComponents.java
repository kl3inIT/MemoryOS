package io.memoryos.channel;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What the process that receives ({@code memoryos-channels}) runs: the channel capability, its supervisor, and
 * everything a Chat turn reaches, which is most of {@code core}. A turn reads the catalog and calls a model
 * ({@code ai}), searches ({@code retrieval}, {@code connector} with every external Source type's adapter,
 * {@code document}), reads and writes files ({@code library}, {@code objectstorage}), may call MCP tools
 * ({@code mcp}), is checked against the spend limits and recorded ({@code usage}, {@code audit}) and authorizes
 * through {@code iam}. Left out: what serves a browser or another process only, which is identity administration
 * through Keycloak, invitations, identity providers, ingestion, meetings and voice.
 *
 * <p>The process imports this class; it is no component, so the API, which scans all of {@code io.memoryos},
 * neither registers these beans twice nor starts the supervisor.
 */
@ComponentScan(
        basePackages = {
                "io.memoryos.channel",
                "io.memoryos.chat",
                "io.memoryos.ai",
                "io.memoryos.retrieval",
                "io.memoryos.connector",
                "io.memoryos.document",
                "io.memoryos.library",
                "io.memoryos.objectstorage",
                "io.memoryos.mcp",
                "io.memoryos.usage",
                "io.memoryos.audit",
                "io.memoryos.iam",
                "io.memoryos.sandbox"
        },
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                "io\\.memoryos\\.iam\\.(keycloak|invitation|identityprovider)\\..*",
                // Signing in, and the MemoryOS MCP endpoint with the apps trusted at it, are Keycloak's and the API's.
                "io\\.memoryos\\.iam\\.(identity\\.DefaultSignInAdmission|JitAllowlistSeeder)",
                "io\\.memoryos\\.mcp\\.(McpTrustedAppService|McpEndpoint[A-Za-z]*)",
                // The library page's shelves read meetings, which this process does not run.
                "io\\.memoryos\\.library\\.LibraryShelfService",
                "io\\.memoryos\\.channel\\.ChannelRuntimeComponents"
        })
)
@EntityScan({"io.memoryos.iam", "io.memoryos.ai.persistence", "io.memoryos.chat", "io.memoryos.mcp.persistence"})
@EnableJpaRepositories(basePackages = {"io.memoryos.ai.persistence", "io.memoryos.chat", "io.memoryos.iam",
        "io.memoryos.mcp.persistence"})
@Import(ChannelSupervisor.class)
public class ChannelRuntimeComponents {
}
