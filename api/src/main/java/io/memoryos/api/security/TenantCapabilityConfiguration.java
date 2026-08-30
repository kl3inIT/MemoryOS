package io.memoryos.api.security;

import io.arconia.multitenancy.core.exceptions.TenantVerificationException;
import io.arconia.multitenancy.core.tenantdetails.TenantVerifier;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.InitialTenantBootstrapRequest;
import io.memoryos.tenant.InitialTenantBootstrapper;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsInitialTenantProperties.class)
class TenantCapabilityConfiguration {
    @Bean
    TenantVerifier tenantVerifier(TenantAccessResolver accessResolver) {
        return tenantIdentifier -> {
            TenantId tenantId;
            try {
                tenantId = new TenantId(UUID.fromString(tenantIdentifier));
            } catch (IllegalArgumentException exception) {
                throw new TenantVerificationException("tenant identifier must be a UUID", exception);
            }
            if (!accessResolver.isActiveTenant(tenantId)) {
                throw new TenantVerificationException("tenant is not active");
            }
        };
    }


    @Bean
    ApplicationRunner initialTenantBootstrapRunner(
            InitialTenantBootstrapper bootstrapper,
            MemoryOsInitialTenantProperties properties,
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