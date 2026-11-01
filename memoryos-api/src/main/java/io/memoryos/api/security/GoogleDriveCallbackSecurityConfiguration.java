package io.memoryos.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class GoogleDriveCallbackSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain googleDriveCallbackSecurityFilterChain(HttpSecurity http) {
        http.securityMatcher("/login/oauth2/code/google-drive")
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
