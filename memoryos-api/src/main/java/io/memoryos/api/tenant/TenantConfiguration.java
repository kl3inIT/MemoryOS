package io.memoryos.api.tenant;

import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.shared.TenantId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the idempotent initial-Tenant bootstrap.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InitialTenantProperties.class)
class TenantConfiguration {

    @Bean
    ApplicationRunner initialTenantBootstrapRunner(
            InitialTenantBootstrapper bootstrapper,
            InitialTenantProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer
    ) {
        return ignored -> bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                new TenantId(properties.id()),
                new ExternalIdentity(issuer, properties.ownerSubject()),
                properties.slug(),
                properties.displayName(),
                properties.changeReference()
        ));
    }
}
