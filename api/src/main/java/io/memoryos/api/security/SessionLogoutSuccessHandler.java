package io.memoryos.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@SuppressWarnings("HttpHeaderInspection")
final class SessionLogoutSuccessHandler implements LogoutSuccessHandler {

    static final String LOGOUT_LOCATION_HEADER = "X-MemoryOS-Logout-Location";
    private static final String END_SESSION_ENDPOINT = "end_session_endpoint";

    private final URI endSessionEndpoint;
    private final String clientId;

    SessionLogoutSuccessHandler(ClientRegistration clientRegistration) {
        this.clientId = clientRegistration.getClientId();
        this.endSessionEndpoint = resolveEndSessionEndpoint(clientRegistration);
    }

    @Override
    public void onLogoutSuccess(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @Nullable Authentication authentication
    ) {
        String postLogoutRedirectUri = ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(request.getContextPath() + "/")
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
        String providerLogoutUri = UriComponentsBuilder.fromUri(endSessionEndpoint)
                .queryParam("client_id", clientId)
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                .build()
                .encode()
                .toUriString();

        response.setHeader(LOGOUT_LOCATION_HEADER, providerLogoutUri);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static URI resolveEndSessionEndpoint(ClientRegistration clientRegistration) {
        var providerDetails = clientRegistration.getProviderDetails();
        Object configuredEndpoint = providerDetails.getConfigurationMetadata().get(END_SESSION_ENDPOINT);
        if (configuredEndpoint instanceof String endpoint && !endpoint.isBlank()) {
            return requireAbsoluteUri(endpoint);
        }
        String issuer = providerDetails.getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "configured OAuth2 provider must publish an end_session_endpoint or issuer URI"
            );
        }
        return requireAbsoluteUri(
                issuer + (issuer.endsWith("/") ? "" : "/") + "protocol/openid-connect/logout"
        );
    }

    private static URI requireAbsoluteUri(String value) {
        var uri = URI.create(value);
        if (!uri.isAbsolute()) {
            throw new IllegalStateException("configured OAuth2 end_session_endpoint must be absolute");
        }
        return uri;
    }
}
