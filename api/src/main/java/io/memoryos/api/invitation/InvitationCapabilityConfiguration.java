package io.memoryos.api.invitation;

import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.invitation.InvitationPersistence;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.OrganizationMembershipProvisioner;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryOsInvitationProperties.class)
class InvitationCapabilityConfiguration {

    @Bean
    OrganizationInvitationService organizationInvitationService(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar,
            OrganizationMembershipProvisioner membershipProvisioner,
            MemoryOsInvitationProperties properties
    ) {
        return InvitationPersistence.invitationService(
                jdbcClient,
                identityResolver,
                identityRegistrar,
                membershipProvisioner,
                Clock.systemUTC(),
                properties.timeToLive(),
                new SecureRandom()
        );
    }
}
