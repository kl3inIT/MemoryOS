package io.memoryos.invitation;

import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.invitation.persistence.JdbcOrganizationInvitationService;
import io.memoryos.organization.OrganizationMembershipProvisioner;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class InvitationPersistence {

    private InvitationPersistence() {
    }

    public static OrganizationInvitationService invitationService(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar,
            OrganizationMembershipProvisioner membershipProvisioner,
            Clock clock,
            Duration timeToLive,
            SecureRandom secureRandom
    ) {
        return new JdbcOrganizationInvitationService(
                Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"),
                Objects.requireNonNull(identityResolver, "identityResolver must not be null"),
                Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null"),
                Objects.requireNonNull(membershipProvisioner, "membershipProvisioner must not be null"),
                Objects.requireNonNull(clock, "clock must not be null"),
                Objects.requireNonNull(timeToLive, "timeToLive must not be null"),
                Objects.requireNonNull(secureRandom, "secureRandom must not be null")
        );
    }
}
