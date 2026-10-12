package io.memoryos.mcp;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MEM-207: an outside assistant the MCP endpoint admits by the URL of its Client ID Metadata Document.
 *
 * @param clientIdHosts the hosts a document's URL may have
 * @param documentHosts the hosts the URIs inside the document may have, such as a callback or a logo
 * @param revision      the row's revision, which a change must name
 */
public record McpTrustedApp(UUID id, Preset preset, String name, List<String> clientIdHosts,
                            List<String> documentHosts, boolean enabled, long revision) {
    public McpTrustedApp {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(name, "name must not be null");
        clientIdHosts = List.copyOf(clientIdHosts);
        documentHosts = List.copyOf(documentHosts);
    }

    public boolean builtIn() {
        return preset != Preset.CUSTOM;
    }

    /**
     * Claude and ChatGPT keep their hosts in code, so a release follows a change of theirs. Claude Code redirects to
     * loopback; ChatGPT's document names its logo on {@code persistent.oaistatic.com}.
     */
    public enum Preset {
        CLAUDE("Claude", List.of("claude.ai", "claude.com"), List.of("claude.ai", "claude.com", "localhost", "127.0.0.1")),
        CHATGPT("ChatGPT", List.of("chatgpt.com"), List.of("chatgpt.com", "persistent.oaistatic.com")),
        CUSTOM("", List.of(), List.of());

        private final String displayName;
        private final List<String> clientIdHosts;
        private final List<String> documentHosts;

        Preset(String displayName, List<String> clientIdHosts, List<String> documentHosts) {
            this.displayName = displayName;
            this.clientIdHosts = clientIdHosts;
            this.documentHosts = documentHosts;
        }

        public String displayName() {
            return displayName;
        }

        public List<String> clientIdHosts() {
            return clientIdHosts;
        }

        public List<String> documentHosts() {
            return documentHosts;
        }

        public static List<Preset> builtIns() {
            return List.of(CLAUDE, CHATGPT);
        }
    }
}
