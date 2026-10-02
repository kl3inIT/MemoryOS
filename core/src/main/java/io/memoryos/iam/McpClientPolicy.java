package io.memoryos.iam;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * MEM-207: the hosts the MCP endpoint's metadata-document policy trusts, and the clients Keycloak built from them. An
 * outside assistant names itself by the URL of a Client ID Metadata Document; Keycloak admits it when the URL's host is
 * among {@link Hosts#clientIdHosts()} and every URI the document lists is among {@link Hosts#documentHosts()}, and keeps
 * one client per document, shared by everyone who connects.
 */
public interface McpClientPolicy {
    /** Whether MemoryOS holds the account that changes the policy; without it nothing is read or written. */
    boolean configured();

    /** The hosts the policy trusts now. */
    Hosts trusted();

    /** Replaces the hosts the policy trusts; the realm's other profiles and policies are left as they are. */
    void trust(Hosts hosts);

    /**
     * Removes every client built from a metadata document whose URL's host {@code hosts} no longer admits, with the
     * grants people gave it, so an app an administrator stops trusting loses its access at once.
     *
     * @return how many clients were removed
     */
    int removeClientsOutside(Hosts hosts);

    /**
     * Hosts compared without case. An entry {@code *.example.com} admits the subdomains of {@code example.com}, as
     * Keycloak's trusted-domain check does.
     */
    record Hosts(Set<String> clientIdHosts, Set<String> documentHosts) {
        public Hosts {
            clientIdHosts = normalized(clientIdHosts);
            documentHosts = normalized(documentHosts);
        }

        public boolean admitsClientIdHost(String host) {
            return admits(clientIdHosts, host);
        }

        private static boolean admits(Set<String> trusted, String host) {
            String candidate = host.toLowerCase(Locale.ROOT);
            for (String entry : trusted) {
                if (entry.startsWith("*.") ? candidate.endsWith(entry.substring(1)) : entry.equals(candidate)) return true;
            }
            return false;
        }

        private static Set<String> normalized(Set<String> hosts) {
            var sorted = new TreeSet<String>();
            for (String host : Objects.requireNonNull(hosts, "hosts must not be null")) {
                sorted.add(host.strip().toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(sorted);
        }
    }
}
