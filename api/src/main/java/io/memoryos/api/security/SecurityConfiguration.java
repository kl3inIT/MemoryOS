package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentityResolver;
import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.SecurityFilterChain;

@NullMarked
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsIdentityProperties.class)
class SecurityConfiguration {

    @Bean
    ExternalIdentityResolver externalIdentityResolver(MemoryOsIdentityProperties properties) {
        return new ConfiguredExternalIdentityResolver(properties);
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            MemoryOsIdentityProperties properties) {
        requireUri(issuerUri, "issuer-uri");
        requireJwkSetUri(jwkSetUri);

        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new JwtAudienceValidator(properties.audience()),
                new RequiredSubjectValidator());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ExternalIdentityResolver identityResolver) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(new JwtToActorAuthenticationConverter(identityResolver))));
        return http.build();
    }

    private static URI requireUri(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        var uri = URI.create(value);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute URI");
        }
        return uri;
    }

    private static void requireJwkSetUri(String value) {
        var uri = requireUri(value, "jwk-set-uri");
        var scheme = uri.getScheme();
        var host = uri.getHost();
        var https = "https".equalsIgnoreCase(scheme) && host != null;
        var loopbackHttp = "http".equalsIgnoreCase(scheme)
                && ("127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host));
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException(
                    "jwk-set-uri must use HTTPS or HTTP with a literal loopback host");
        }
    }

    private static final class RequiredSubjectValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error MISSING_SUBJECT = new OAuth2Error(
                BearerTokenErrorCodes.INVALID_TOKEN,
                "JWT subject must not be blank",
                null);

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            return jwt.getSubject() == null || jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.failure(MISSING_SUBJECT)
                    : OAuth2TokenValidatorResult.success();
        }
    }
}
