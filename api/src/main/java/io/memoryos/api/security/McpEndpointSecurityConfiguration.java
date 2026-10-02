package io.memoryos.api.security;

import io.memoryos.api.mcp.endpoint.McpEndpointGateFilter;
import io.memoryos.api.mcp.endpoint.McpEndpointLimits;
import io.memoryos.api.mcp.endpoint.McpEndpointRequestFilter;
import io.memoryos.mcp.McpEndpointActivity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.McpClientGrants;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.mcp.McpEndpointProperties;
import io.memoryos.mcp.McpEndpointService;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * MEM-114: the security chain of the MemoryOS MCP endpoint, for exactly {@code /mcp} and its protected-resource
 * metadata. It accepts only a bearer token issued for the endpoint: its audience is the endpoint URL, which only the
 * {@code knowledge:read} scope adds. An endpoint token cannot call {@code /api}, whose audience is {@code memoryos-api},
 * and an API token or a browser session cannot call {@code /mcp}. No session is read or created.
 *
 * <p>The actor comes from the token's exact issuer and subject, as on {@code /api}, and IAM is read again on every call.
 */
@NullMarked
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpEndpointLimits.class)
class McpEndpointSecurityConfiguration {
    static final String SCOPE = McpClientGrants.KNOWLEDGE_READ_SCOPE;
    /** The audience of a deployment without an endpoint; no token carries it, and the gate answers 404 first anyway. */
    private static final String NO_ENDPOINT = "urn:memoryos:mcp-endpoint:not-configured";

    @Bean
    @Order(0)
    SecurityFilterChain mcpEndpointSecurityFilterChain(
            HttpSecurity http,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${memoryos.initial-tenant.id}") UUID tenantId,
            McpEndpointProperties endpoint,
            McpEndpointService endpointSwitch,
            McpEndpointLimits limits,
            McpEndpointActivity activity,
            ExternalIdentityResolver identityResolver,
            TenantAccessResolver tenantAccessResolver) {
        String resource = endpoint.url().map(Object::toString).orElse(NO_ENDPOINT);
        String metadata = endpoint.origin().map(origin -> origin.resolve(McpEndpointProperties.METADATA_PATH).toString())
                .orElse(McpEndpointProperties.METADATA_PATH);
        http.securityMatcher(McpEndpointProperties.PATH, McpEndpointProperties.METADATA_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new McpEndpointGateFilter(endpointSwitch, endpoint, new TenantId(tenantId)),
                        DisableEncodeUrlFilter.class)
                .addFilterAfter(new McpEndpointRequestFilter(limits, activity), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(McpEndpointProperties.METADATA_PATH).permitAll()
                        .anyRequest().access((authentication, _) -> new AuthorizationDecision(
                                authentication.get() instanceof ActorAuthenticationToken token
                                        && token.jwt().map(jwt -> jwt.getClaimAsString("scope"))
                                        .map(scopes -> List.of(scopes.split(" ")).contains(SCOPE)).orElse(false)
                                        && tenantAccessResolver.hasActiveTenant(token.getPrincipal().actorId()))))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(challenge(metadata))
                        .protectedResourceMetadata(resourceMetadata -> resourceMetadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(resource)
                                        .authorizationServer(issuerUri)
                                        .tlsClientCertificateBoundAccessTokens(false)
                                        .scopes(scopes -> scopes.add(SCOPE))))
                        .jwt(jwt -> jwt
                                .decoder(decoder(issuerUri, jwkSetUri, resource))
                                .jwtAuthenticationConverter(new JwtToActorAuthenticationConverter(identityResolver))));
        return http.build();
    }

    /** Not a bean: a second {@link JwtDecoder} bean would make the API's decoder ambiguous. */
    private static JwtDecoder decoder(String issuerUri, String jwkSetUri, String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new JwtAudienceValidator(audience),
                new SecurityConfiguration.RequiredSubjectValidator()));
        return decoder;
    }

    /**
     * The 401 names the metadata document (RFC 9728) and the scope to ask for, which is how Claude and ChatGPT find
     * Keycloak and start the sign-in.
     */
    private static AuthenticationEntryPoint challenge(String metadata) {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        bearer.setResourceMetadataParameterResolver(request -> metadata);
        return (request, response, failure) -> {
            bearer.commence(request, response, failure);
            String header = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
            if (header != null && !header.contains("scope=")) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, header + ", scope=\"" + SCOPE + "\"");
            }
        };
    }
}
