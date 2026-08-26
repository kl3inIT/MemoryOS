package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsInitialOrganizationProperties.class)
class OrganizationCapabilityConfiguration {


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
                properties.changeReference()
        ));
    }
}