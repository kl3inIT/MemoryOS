package io.memoryos.api.security;

import io.memoryos.iam.ActorProfileRecorder;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TrustedIdentityAdmission;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BrowserLoginProperties.class, JitAdmissionProperties.class})
class SessionSecurityConfiguration {

    @Bean
    @Order(2)
    SecurityFilterChain sessionSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            ExternalIdentityResolver identityResolver,
            TenantAccessResolver tenantAccessResolver,
            InvitationService invitationService,
            ActorProfileRecorder profileRecorder,
            BrowserLoginProperties browserLoginProperties,
            TrustedIdentityAdmission trustedIdentityAdmission,
            JitAdmissionProperties jitProperties,
            @Value("${memoryos.initial-tenant.id}") UUID tenantId,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String trustedIssuer
    ) {
        var clientRegistration = clientRegistrationRepository.findByRegistrationId(browserLoginProperties.registrationId());
        if (clientRegistration == null) {
            throw new IllegalStateException("configured OAuth2 login client registration does not exist");
        }
        if (clientRegistration.getClientSecret().isBlank()) {
            throw new IllegalStateException("configured OAuth2 login client secret must not be blank");
        }
        RequestMatcher sessionLogoutRequest = request -> HttpMethod.POST.matches(request.getMethod())
                && "/logout".equals(request.getServletPath())
                && BrowserMutation.isPresent(request.getHeader(BrowserMutation.HEADER));

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/access-not-provisioned",
                                "/invite/**",
                                "/oauth2/authorization/**",
                                "/login/oauth2/code/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.ignoringRequestMatchers(sessionLogoutRequest))
                .oauth2Login(oauth2 -> oauth2
                        .authorizedClientRepository(new DiscardingOAuth2AuthorizedClientRepository())
                        .successHandler(new ActorSessionLoginSuccessHandler(
                                identityResolver,
                                tenantAccessResolver,
                                invitationService,
                                profileRecorder,
                                trustedIdentityAdmission,
                                jitProperties,
                                new TenantId(tenantId),
                                trustedIssuer
                        ))
                        .failureHandler(new OAuth2LoginFailureHandler()))
                .logout(logout -> logout
                        .logoutRequestMatcher(sessionLogoutRequest)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION")
                        .logoutSuccessHandler(new SessionLogoutSuccessHandler(clientRegistration)));
        return http.build();
    }
}
