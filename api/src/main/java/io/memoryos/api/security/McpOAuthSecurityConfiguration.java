package io.memoryos.api.security;

import io.memoryos.mcp.McpOAuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The MCP authorization callback and Client ID Metadata Document sit outside Spring's OAuth2 login paths. The
 * callback authorizes itself from the session-bound pending state; the metadata document is public by design.
 */
@Configuration(proxyBeanMethods = false)
class McpOAuthSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain mcpOAuthSecurityFilterChain(HttpSecurity http) {
        http.securityMatcher(McpOAuthProperties.CALLBACK_PATH, McpOAuthProperties.CLIENT_METADATA_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
