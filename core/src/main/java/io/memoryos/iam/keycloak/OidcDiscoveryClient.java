package io.memoryos.iam.keycloak;

import io.memoryos.iam.DiscoveredOidcProvider;
import io.memoryos.iam.IdentityProviderException;
import io.memoryos.iam.IdentityProviderFailureReason;
import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;

import java.net.URI;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches and validates an upstream issuer's {@code .well-known/openid-configuration}. HTTPS is
 * required except for literal loopback hosts; the document's {@code issuer} must match the input
 * exactly so a redirect or typo cannot silently retarget the provider.
 */
@Component
public class OidcDiscoveryClient {

    /** A discovery document is a few kilobytes; MCP authorization metadata, the same kind of document, has this bound. */
    private static final Limits LIMITS = new Limits(Duration.ofSeconds(10), 65536);
    private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

    private final RestClient client = OutboundHttp.builder(LIMITS).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DiscoveredOidcProvider discover(String issuerUrl) {
        Assert.hasText(issuerUrl, "issuerUrl must not be blank");
        URI issuer = parseIssuer(issuerUrl);
        URI discoveryUri = issuer.resolve(
                issuer.getPath() == null || issuer.getPath().endsWith("/")
                        ? issuer.getPath() + ".well-known/openid-configuration"
                        : issuer.getPath() + DISCOVERY_SUFFIX
        );

        byte[] body;
        try {
            body = client.get().uri(discoveryUri).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                    throw invalidDiscovery("discovery returned HTTP " + status);
                }
                return response.getBody().readAllBytes();
            });
        } catch (RestClientException exception) {
            if (exception.getCause() instanceof ResponseTooLargeException) {
                throw invalidDiscovery("discovery document is larger than " + LIMITS.maxResponseBytes() + " bytes");
            }
            // The transport failure itself: Spring's message repeats the URL, which the diagnostic already names.
            throw unavailable("discovery request to " + discoveryUri + " failed",
                    exception.getCause() == null ? exception : exception.getCause());
        }

        JsonNode document;
        try {
            document = objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw invalidDiscovery("discovery document is not valid JSON");
        }
        if (!issuerUrl.equals(textOrNull(document, "issuer"))) {
            throw invalidDiscovery("discovery issuer does not match the requested issuer");
        }
        return new DiscoveredOidcProvider(
                issuerUrl,
                requiredText(document, "authorization_endpoint"),
                requiredText(document, "token_endpoint"),
                optionalText(document, "end_session_endpoint"),
                optionalText(document, "userinfo_endpoint"),
                requiredText(document, "jwks_uri")
        );
    }

    private static URI parseIssuer(String issuerUrl) {
        URI issuer;
        try {
            issuer = URI.create(issuerUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidDiscovery("issuer URL is not a valid URI");
        }
        boolean loopback = "localhost".equalsIgnoreCase(issuer.getHost())
                || "127.0.0.1".equals(issuer.getHost())
                || "::1".equals(issuer.getHost());
        if (!"https".equalsIgnoreCase(issuer.getScheme())
                && !("http".equalsIgnoreCase(issuer.getScheme()) && loopback)) {
            throw invalidDiscovery("issuer URL must use https (http is allowed only for loopback)");
        }
        if (issuer.getHost() == null || issuer.getRawQuery() != null || issuer.getRawFragment() != null) {
            throw invalidDiscovery("issuer URL must be an absolute URI without query or fragment");
        }
        return issuer;
    }

    private static String requiredText(JsonNode document, String field) {
        String value = optionalText(document, field);
        if (value == null) {
            throw invalidDiscovery("discovery document is missing " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode document, String field) {
        String value = textOrNull(document, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static String textOrNull(JsonNode document, String field) {
        JsonNode node = document.path(field);
        return node.isString() ? node.stringValue() : null;
    }

    private static IdentityProviderException invalidDiscovery(String diagnosticMessage) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.DISCOVERY_FAILED,
                diagnosticMessage
        );
    }

    private static IdentityProviderException unavailable(String diagnosticMessage, Throwable cause) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.PROVIDER_UNAVAILABLE,
                diagnosticMessage,
                cause
        );
    }
}
