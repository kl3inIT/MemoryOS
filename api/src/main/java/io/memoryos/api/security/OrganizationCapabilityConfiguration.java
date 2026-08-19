package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.identity.IdentityPersistence;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.OrganizationAccessResolver;
import io.memoryos.organization.OrganizationPersistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsInitialOrganizationProperties.class)
class OrganizationCapabilityConfiguration {

    @Bean
    ExternalIdentityResolver externalIdentityResolver(JdbcClient jdbcClient) {
        return IdentityPersistence.resolver(jdbcClient);
    }

    @Bean
    ExternalIdentityRegistrar externalIdentityRegistrar(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver
    ) {
        return IdentityPersistence.registrar(jdbcClient, identityResolver);
    }

    @Bean
    OrganizationAccessResolver organizationAccessResolver(JdbcClient jdbcClient) {
        return OrganizationPersistence.accessResolver(jdbcClient);
    }

    @Bean
    InitialOrganizationBootstrapper initialOrganizationBootstrapper(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar
    ) {
        return OrganizationPersistence.initialBootstrapper(jdbcClient, identityResolver, identityRegistrar);
    }

    @Bean
    ApplicationRunner initialOrganizationBootstrapRunner(
            InitialOrganizationBootstrapper bootstrapper,
            MemoryOsInitialOrganizationProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer
    ) {
        return ignored -> bootstrapper.bootstrap(new InitialOrganizationBootstrapRequest(
                new ExternalIdentity(issuer, properties.ownerSubject()),
                properties.slug(),
                properties.displayName(),
                properties.defaultWorkspaceSlug(),
                properties.defaultWorkspaceDisplayName(),
                properties.changeReference()
        ));
    }
}