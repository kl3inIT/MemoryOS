package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.OrganizationAccessResolver;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsBrowserProperties.class)
class BrowserSecurityConfiguration {

    @Bean
    @Order(3)
    SecurityFilterChain browserSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations,
            ExternalIdentityResolver identityResolver,
            OrganizationAccessResolver organizationAccessResolver,
            OrganizationInvitationService invitationService,
            MemoryOsBrowserProperties browserProperties
    ) {
        var clientRegistration = clientRegistrations.findByRegistrationId(browserProperties.registrationId());
        if (clientRegistration == null) {
            throw new IllegalStateException("configured browser OAuth client registration does not exist");
        }
        if (clientRegistration.getClientSecret().isBlank()) {
            throw new IllegalStateException("configured browser OAuth client secret must not be blank");
        }

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
                .oauth2Login(oauth2 -> oauth2
                        .authorizedClientRepository(new DiscardingOAuth2AuthorizedClientRepository())
                        .successHandler(new ActorSessionAuthenticationSuccessHandler(
                                identityResolver,
                                organizationAccessResolver,
                                invitationService
                        ))
                        .failureHandler(new BrowserAuthenticationFailureHandler()))
                .logout(logout -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION")
                        .logoutSuccessUrl("/access-not-provisioned"));
        return http.build();
    }
}