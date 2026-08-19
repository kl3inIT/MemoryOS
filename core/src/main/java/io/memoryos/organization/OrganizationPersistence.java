package io.memoryos.organization;

import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.organization.persistence.JdbcInitialOrganizationBootstrapper;
import io.memoryos.organization.persistence.JdbcOrganizationAccessResolver;

import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class OrganizationPersistence {

    private OrganizationPersistence() {
    }

    public static OrganizationAccessResolver accessResolver(JdbcClient jdbcClient) {
        return new JdbcOrganizationAccessResolver(
                Objects.requireNonNull(jdbcClient, "jdbcClient must not be null")
        );
    }

    public static InitialOrganizationBootstrapper initialBootstrapper(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar
    ) {
        return new JdbcInitialOrganizationBootstrapper(
                Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"),
                Objects.requireNonNull(identityResolver, "identityResolver must not be null"),
                Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null")
        );
    }
}